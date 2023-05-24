package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 获取当前页博客数据
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        // 查询并设置当前页博客的相关用户信息和是否点赞信息
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        // 查询并设置当前页博客的相关用户信息
        queryBlogUser(blog);
        // 设置blog是否被点赞（前端点赞图标高亮与否）
        isBlogLiked(blog);
        return Result.ok(blog);
    }
    // 设置是否被点赞信息
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        // isBlogLiked在首页也被使用到，但是首页展示不需要用户登录
        if (user == null){
            // 用户未登录，无需查询用户是否点赞
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
    // 点赞功能
    @Override
    public Result likeBlog(Long id) {
        // 1、判断当前登录用户是否已经点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 未点过赞
        if (score == null){
            // 更新DB，点赞+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 存入redis缓存，采用sortedSet数据结构（按分数排序），实现点赞排行榜功能。 zadd key value score
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 更新DB，点赞-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 移出redis缓存
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞排行(新的点赞排在前面)
     * @param id blog id
     * @return result
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户 listByIds方法：where id in (ids)，in在数据库中查询的数据不会按ids排序，不满足需求
        // 指定按id字段ids排序:where id in (ids) order by field(id,(ids))
        //SELECT * FROM user
        //WHERE id IN (id1, id2, ..., idn)
        //ORDER BY FIELD(id, id1, id2, ..., idn)
        String idStr = StrUtil.join(",", ids);//id1, id2, ..., idn
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 保存探店笔记，并推送给粉丝儿
     * @param blog blog
     * @return result
     */
    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发送笔记失败");
        }
        // 查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送笔记给每个粉丝的收件箱（SortedSet）(通过SortedSet缓存加速，并且SortedSet支持排序，
        // 此外，SortedSet相比于list可以实现滚动分页，因为它不通过索引排序而是score)
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页
     * @param max sortedSet的max
     * @param offset sortedSet的偏移量
     * @return Result
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        // zrevrangebyscore key max min limit offset count
        String key = FEED_KEY + userId;
        // 返回sortedSet中0到max分数之间，按分数从大到小排序的的第offset到第2个元素
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 解析数据，得到blogId,minTime(score),offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; //offsite
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os ++;
            }else {
                minTime = time;
            }
        }
        // 根据ids查询相关的blog,为了保持顺序：ORDER BY FIELD（）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 给blog设置完整的相 关信息
        for (Blog blog : blogs) {
            // 查询blog相关用户
            queryBlogUser(blog);
            // 设置blog是否被点赞（前端点赞图标高亮与否）
            isBlogLiked(blog);
        }
        // 封装返回数据
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    /**
     * 查询并设置blog的相关用户的name和icon
     * @param blog blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
