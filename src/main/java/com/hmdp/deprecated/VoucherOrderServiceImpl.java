package com.hmdp.deprecated;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import static com.hmdp.utils.ErrorMessageConstants.*;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_LOCK_KEY;

/**
 * 没有异步更新的优惠券秒杀业务
 */

//@Service
//public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
//    @Resource
//    private ISeckillVoucherService seckillVoucherService;
//
//    @Resource
//    private RedisWorker redisWorker;
//
//    @Resource
//    private RedissonClient redissonClient;
//
//    /**
//     * 优惠券秒杀。CAS（compare and set）乐观锁解决超卖问题。
//     *           悲观锁解决一人一单问题。
//     * @param voucherId voucherId
//     * @return false/ok
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher == null){
//            return Result.fail("秒杀券不存在");
//        }
//        // 2、秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀券不存在");
//        }
//        // 3、秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//        // 4. 库存是否充足
//        if (seckillVoucher.getStock() < 1){
//            return Result.fail("秒杀已抢光");
//        }
//        // 悲观锁实现一人一单
//        // 只对同一个userId加锁。”创建订单“为一个事务，需要对整个事务加锁来预防安全问题。
//        // .intern()：将一个字符串对象添加到字符串池中，并返回字符串池中的字符串对象的引用。
//        // 这样，如果有多个字符串对象具有相同的内容，则它们都会指向字符串池中的同一个对象。
//        Long userId = UserHolder.getUser().getId();
//        // redissonClient创建分布式锁对象
//        // 它通过redis的hash数据结构实现可重入，如（key : lock value）,value记录重入的线程数。
//        // 通过开启watchDog（leaseTime=-1时开启）来实现超时释放的问题，watchDog每过一段时间就去重置lock的有效期。
//        // 通过设置线程获取锁的重试时间waiTime来实现可重试，如果获取失败，该线程在waiTime内重新进行尝试获取该锁，但
//        // 不是立即去重试，而是在waiTime内通过订阅（sub）到该锁的释放信号（锁释放时会发布（pub）一个释放信号）再去尝试。等待
//        // 的时间超过waiTime直接返回false
//        // redisson通过lua脚本实现相关的原子操作
//        // （redis主从模式下，redisson通过multiLock来实现数据一致性）
//        RLock lock = redissonClient.getLock(SECKILL_ORDER_LOCK_KEY + userId);
//        // 尝试获取锁
//        boolean hasLock = lock.tryLock();
//        if (!hasLock) {
//            return Result.fail("不要重复下单");
//        }
//        try{
//             /*createVoucherOrder()具有事务，如果直接return createVoucherOrder(voucherId, seckillVoucher);
//             那么调用的是this的createVoucherOrder方法，不是代理对象的createVoucherOrder
//             （spring aop事务会创建代理对象来执行事务方法），所以事务不会起作用。
//
//             获取事务代理对象（需要依赖aspectjweaver包以及@EnableAspectJAutoProxy(exposeProxy = true)注解）
//             AopContext.currentProxy()通过 AspectJ 织入的字节码实现，通过AopContext可
//             以在代理类内部获取当前代理对象。默认情况下，由于安全性考虑，Spring 不会将代理对象暴露给用户代码。
//             而@EnableAspectJAutoProxy(exposeProxy = true)注解可以将代理对象暴露出来*/
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, seckillVoucher);
//        }finally {
//            lock.unlock();
//        }
//        //synchronized锁只在单系统中有效，在分布式系统下无效，因为服务器之间不共享JVM锁
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId, seckillVoucher);
////        }
//    }
//
//    // 创建订单
//    @Transactional
//    public Result createVoucherOrder(Long voucherId, SeckillVoucher seckillVoucher) {
//        // 5、一人一单
//        Long userId = UserHolder.getUser().getId();
//        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0 ){
//            return Result.fail("用户已经抢到");
//        }
//        /* 6. 扣减库存，乐观锁。(.eq(SeckillVoucher::getStock, seckillVoucher.getStock())判断库存是否一致，
//         不一致说明被修改过，CAS型乐观锁，可以节省一个VERSION字段)。但是这种
//         乐观锁判断方法虽然安全失败率很高，比如库存还剩80，但是并发的线程发现stock不一致，则失败了。
//         而.gt(SeckillVoucher::getStock, 0)，即判断stock是否>0，则可以解决该问题*/
//        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
//        wrapper.eq(SeckillVoucher::getVoucherId, seckillVoucher.getVoucherId())
//                .gt(SeckillVoucher::getStock, 0)
//                .set(SeckillVoucher::getStock, seckillVoucher.getStock() - 1);
//        boolean success = seckillVoucherService.update(wrapper);
//        if (!success){
//            return Result.fail(SECKILL_NONE);
//        }
//        // 7、创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId)
//                .setUserId(userId)
//                .setVoucherId(voucherId);
//        this.save(voucherOrder);
//        // 8、返回订单id
//        return Result.ok(orderId);
//
//    }
//}
