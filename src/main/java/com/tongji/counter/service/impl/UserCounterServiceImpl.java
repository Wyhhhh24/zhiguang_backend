package com.tongji.counter.service.impl;

import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户维度计数服务实现。
 *
 * <p>职责：</p>
 * - 异步维护关注/粉丝/发文/获赞/获收藏计数（SDS）；
 * - 提供按需重建能力以纠偏异常；
 * - 重建过程聚合作者所有内容的获赞/获收藏总数。
 */
@Service
public class UserCounterServiceImpl implements UserCounterService {
    private final StringRedisTemplate redis;

    /**
     * Lua 脚本
     */
    private final DefaultRedisScript<Long> incrScript;

    /**
     * 知文持久层服务
     */
    private final KnowPostMapper knowPostMapper;

    /**
     * 笔记维度计数 服务
     */
    private final CounterService counterService;

    /**
     * 关系表持久层服务
     */
    private final RelationMapper relationMapper;

    public UserCounterServiceImpl(StringRedisTemplate redis,
                                  KnowPostMapper knowPostMapper,
                                  CounterService counterService,
                                  RelationMapper relationMapper) {
        this.redis = redis;
        this.knowPostMapper = knowPostMapper;
        this.counterService = counterService;
        this.relationMapper = relationMapper;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        // 用户维度计数原子折叠（1 基坐标）
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }
    /**
     * SDS 分为 5 个部分：1：关注数、2：粉丝数、3：发文数、4：获赞数（作者维度，总共收获了多少赞）、5：收藏数（作者维度，总共被收藏了多少篇）
     */

    /** 增量更新关注数 */
    @Override
    public void incrementFollowings(long userId, int delta) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "1", String.valueOf(delta));
    }

    /** 增量更新粉丝数 */
    @Override
    public void incrementFollowers(long userId, int delta) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "2", String.valueOf(delta));
    }

    /** 增量更新发文数 */
    @Override
    public void incrementPosts(long userId, int delta) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "3", String.valueOf(delta));
    }

    /** 增量更新获赞数（作者维度） */
    @Override
    public void incrementLikesReceived(long userId, int delta) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "4", String.valueOf(delta));
    }

    /** 增量更新收藏数（作者维度） */
    @Override
    public void incrementFavsReceived(long userId, int delta) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "5", String.valueOf(delta));
    }

    /** 基于事实重建全部用户维度计数 */
    @Override
    public void rebuildAllCounters(long userId) {
        // 基于用户 ID 生成用户维度计数键
        String key = UserCounterKeys.sdsKey(userId);
        // 获取该计数键对应的 SDS
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        int len = 5 * 4;
        byte[] buf = new byte[len];
        // public static native void arraycopy(
        //    Object src,     // 源数组
        //    int srcPos,     // 源数组起始位置
        //    Object dest,    // 目标数组
        //    int destPos,    // 目标数组起始位置
        //    int length      // 要复制的元素数量
        //);
        if (raw != null && raw.length == len) {
            // 保留已存在的值，按需覆盖
            System.arraycopy(raw, 0, buf, 0, len);
            //            源数组、源数组起始位置、目标数组、目标数组起始位置、要复制的元素数量
        }
        // 从数据库读取 关注数（当前用户关注了谁）、粉丝数（谁关注了该用户）
        long followings = relationMapper.countFollowingActive(userId);
        long followers = relationMapper.countFollowerActive(userId);

        long posts;
        // 获取当前用户已发布的知文 ID 列表
        List<Long> ids = knowPostMapper.listMyPublishedIds(userId);
        // 将 ids 转换成字符串类型的 List
        List<String> idStr = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());

        // 如果知文 ID 列表不为空
        if (!idStr.isEmpty()) {
            // 通过知文 ID 列表，获取当前用户已经发布了多少篇知文
            posts = idStr.size();
            // 定义获赞、收藏数变量
            long likeSum = 0L;
            long favSum = 0L;
            Map<String, Map<String, Long>> counts = counterService.getCountsBatch("knowpost", idStr, List.of("like", "fav"));

            // 获取知文 ID 对应的 like/fav 的计数之后，也就是 counts ，进行遍历，聚合作者全部知文的获赞/获收藏总数
            for (String id : idStr) {
                Map<String, Long> v = counts.get(id);
                likeSum += v.getOrDefault("like", 0L);
                favSum += v.getOrDefault("fav", 0L);
            }
            write32be(buf, 2 * 4, posts); // 发布的文章数
            write32be(buf, 3 * 4, likeSum); // 总点赞数
            write32be(buf, 4 * 4, favSum); // 总收藏数
        } else {
            // 如果列表为空，每项设置默认值 0
            write32be(buf, 2 * 4, 0L);
            write32be(buf, 3 * 4, 0L);
            write32be(buf, 4 * 4, 0L);
        }
        write32be(buf, 0, followings); // 当前用户关注数
        write32be(buf, 4, followers); // 当前用户粉丝数

        // 构建好 SDS 之后，回写用户计数 SDS
        redis.execute((RedisCallback<Void>) c -> {
            c.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), buf);
            return null;
        });
    }

    private static final String INCR_FIELD_LUA = """
            -- 1. 变量初始化与参数解析
            local cntKey = KEYS[1] -- Redis 的 Key 名
            local schemaLen = tonumber(ARGV[1]) -- 总字段数量（Schema 长度）
            local fieldSize = tonumber(ARGV[2]) -- 每个字段占用的字节数（本脚本中固定处理 4 字节）
            local idx = tonumber(ARGV[3]) -- 当前要操作的字段索引（从 1 开始）
            local delta = tonumber(ARGV[4]) -- 增量值（加多少或减多少）
            
            -- 2.定义大端序读取函数：从二进制字符串 s 的指定偏移 off 处读取 4 字节，并将其转为 Lua 的数值
            local function read32be(s, off)
              -- 从偏移量 off+1 开始取出 4 个字节
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              -- 将字节转换为 32 位整数
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            
            -- 3. 定义大端序写入函数：将一个 Lua 数值转回为 4 字节的二进制字符串
            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end
            
            -- 4.获取对应的二进制字符串，若不存在，进行初始化
            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            
            -- 5.计算目标字段在二进制串中的偏移量
            local off = (idx - 1) * fieldSize
            
            -- 6.读取原值并加上增量 delta
            local v = read32be(cnt, off) + delta
            
            -- 7.边界保护：确保计数器不小于 0
            if v < 0 then v = 0 end
            
            -- 8.将新值转为二进制片段
            local seg = write32be(v)
            
            -- 9.利用 string.sub 将旧串拆开，把修改后的 4 字节片段 seg 塞回去，再拼接
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            
            -- 10.将修改好的二进制字符串，写回 Redis 中
            redis.call('SET', cntKey, cnt)
            
            -- 11.返回成功标识
            return 1
            """;

    private static long read32be(byte[] buf, int off) {
        if (buf == null || buf.length < off + 4) return 0L;
        long n = 0L;
        for (int i = 0; i < 4; i++) n = (n << 8) | (buf[off + i] & 0xFFL);
        return n;
    }

    private static void write32be(byte[] buf, int off, long val) {
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }
}

