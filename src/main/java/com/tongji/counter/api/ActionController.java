package com.tongji.counter.api;

import com.tongji.counter.api.dto.ActionRequest;
import com.tongji.counter.service.CounterService;
import com.tongji.auth.token.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 点赞、收藏行为接口：点赞/取消点赞、收藏/取消收藏。
 *
 * <p>所有接口基于登录用户，返回操作是否改变状态以及当前状态值。</p>
 */
@RestController
@RequestMapping("/api/v1/action")
public class ActionController {

    /**
     * 计数服务
     */
    private final CounterService counterService;

    /**
     * JWT 令牌服务
     */
    private final JwtService jwtService;

    public ActionController(CounterService counterService, JwtService jwtService) {
        this.counterService = counterService;
        this.jwtService = jwtService;
    }

    /**
     * 点赞操作。
     */
    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@Valid @RequestBody ActionRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        // 解析 JWT 获取 userId
        long uid = jwtService.extractUserId(jwt);
        // 基于用户Id+实体类型+实体Id，对相应的事件进行点赞，获取状态是否改变的标识
        boolean changed = counterService.like(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
                                 // 再次查询对应的 SDS 判断该实体此时的状态，是否已点赞
        ));
    }

    /**
     * 取消点赞操作。
     */
    @PostMapping("/unlike")
    public ResponseEntity<Map<String, Object>> unlike(@Valid @RequestBody ActionRequest req,
                                                      @AuthenticationPrincipal Jwt jwt) {
        // 解析 JWT 获取 userId
        long uid = jwtService.extractUserId(jwt);
        // 基于用户Id+实体类型+实体Id，对相应的事件进行取消点赞，获取状态是否改变的标识
        boolean changed = counterService.unlike(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
                              // 再次查询对应的 SDS 判断该实体此时的状态，是否已取消点赞
        ));
    }

    /**
     * 收藏操作。
     */
    @PostMapping("/fav")
    public ResponseEntity<Map<String, Object>> fav(@Valid @RequestBody ActionRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        // 解析 JWT 获取 userId
        long uid = jwtService.extractUserId(jwt);
        // 基于用户Id+实体类型+实体Id，对相应的事件进行收藏，获取状态是否改变的标识
        boolean changed = counterService.fav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "faved", counterService.isFaved(req.getEntityType(), req.getEntityId(), uid)
                      // 再次查询对应的 SDS 判断该实体此时的状态，是否已收藏
        ));
    }

    /**
     * 取消收藏操作。
     */
    @PostMapping("/unfav")
    public ResponseEntity<Map<String, Object>> unfav(@Valid @RequestBody ActionRequest req,
                                                     @AuthenticationPrincipal Jwt jwt) {
        // 解析 JWT 获取 userId
        long uid = jwtService.extractUserId(jwt);
        // 基于用户Id+实体类型+实体Id，对相应的事件进行取消收藏，获取状态是否改变的标识
        boolean changed = counterService.unfav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "faved", counterService.isFaved(req.getEntityType(), req.getEntityId(), uid)
                     // 再次查询对应的 SDS 判断该实体此时的状态，是否已收藏
        ));
    }
}