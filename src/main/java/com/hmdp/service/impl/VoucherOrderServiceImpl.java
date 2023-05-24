package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.hmdp.utils.ErrorMessageConstants.*;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_LOCK_KEY;


@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisWorker redisWorker;

    @Resource
    private RedissonClient redissonClient;

    // 静态加载seckill lua脚本，避免每次执行脚本时都要重新读取和解析脚本文件的开销
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT; //Long为返回值类型
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建异步更新DB的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 当前类初始化完毕执行该方法
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 子线程异步更新DB
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    // 获取stream消息队列中的订单信息，没有则阻塞
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断获取消息是否成功
                    if (list == null || list.isEmpty()){
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // ACK消息确认（解决消息丢失的机制）
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    // 获取pending list中的订单信息，没有则阻塞
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断获取消息是否成功
                    if (list == null || list.isEmpty()){
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // stream pending list 的ACK消息确认（解决消息丢失的机制）
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending list异常", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }

    // 子线程需要执行createVoucherOrder（在主线程），需要得到它具有事务的代理对象，
    // 通过全局变量实现跨线程传递消息proxy
    private IVoucherOrderService proxy;
    /**
     *  优惠券秒杀。1、CAS（compare and set）乐观锁解决超卖问题。
     *            2、悲观锁解决一人一单问题。
     *            3、redis消息队列实现异步更新DB，优化秒杀。（子进程操作）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextId("order");
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 判断
        assert result != null;
        int i = result.intValue();
        // 没有购买资格
        if (i != 0) {
            return Result.fail(i == 1 ? SECKILL_NONE : SECKILL_NOT_REPEAT);
        }

        // 获取VoucherOrderService的代理对象给异步子线程执行createVoucherOrder事务
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // redissonClient创建分布式锁对象
        RLock lock = redissonClient.getLock(SECKILL_ORDER_LOCK_KEY + userId);
        // 尝试获取锁
        boolean hasLock = lock.tryLock();
        if (!hasLock) {
            log.error(SECKILL_NOT_REPEAT);
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    // 创建订单
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5、一人一单
        Long userId = voucherOrder.getUserId();
        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0 ){
            log.error(SECKILL_HOLD);
            return;
        }
        // 更新库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stokc - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success){
            log.error(SECKILL_NONE);
            return;
        }
        // 保存订单
        this.save(voucherOrder);

    }
}

/*
    // BlockingQueue：JDK提供的基于内存的阻塞队列(当一个线程尝试从BlockingQueue中获取元素时，
    // 如果队列中不存在该元素，则线程被阻塞，直到该元素在队列中存在，线程才被唤醒并获取
    // 到该元素)。 缺陷：1、内存限制。2、安全问题（宕机，内存丢失） --> redis stream group
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程任务,异步下单(JVM实现)
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 获取队列中的订单信息，没有则阻塞
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 判断
        assert result != null;
        int i = result.intValue();
        // 没有购买资格
        if (i != 0) {
            return Result.fail(i == 1 ? SECKILL_NONE : SECKILL_NOT_REPEAT);
        }
        // 有购买资格
        long orderId = redisWorker.nextId("order");
        // 保存下单信息到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder().setId(orderId)
                                                      .setUserId(userId)
                                                      .setVoucherId(voucherId);
        // 异步下单
        orderTasks.add(voucherOrder);

        // 获取VoucherOrderService的代理对象给异步子线程执行createVoucherOrder事务
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/
