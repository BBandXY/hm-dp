package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.marketing.TaskEventService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/** 用户登录与 Bitmap 签到服务。 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DateTimeFormatter SIGN_MONTH_FORMATTER = DateTimeFormatter.ofPattern(":yyyyMM");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TaskEventService taskEventService;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("登录验证码已生成并写入缓存，手机号尾号={}", phone.substring(phone.length() - 4));
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();
        boolean newUser = user == null;
        if (newUser) {
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        if (newUser) {
            taskEventService.recordSafely(
                    user.getId(), MarketingConstants.TASK_NEW_USER_LOGIN, user.getId().toString()
            );
        }
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String key = signKey(userId, now.toLocalDate());
        int offset = now.getDayOfMonth() - 1;

        stringRedisTemplate.opsForValue().setBit(key, offset, true);
        // 事件表会幂等去重；重复签到仍上报一次，使营销库临时故障后可由用户重试修复进度。
        String today = now.toLocalDate().toString();
        taskEventService.recordSafely(userId, MarketingConstants.TASK_DAILY_SIGN, today);

        // 连续 7 天任务是一次性成就；跨月时按日期切换 Bitmap key 仍能正确判断。
        if (hasContinuousSigns(userId, now.toLocalDate(), 7)) {
            taskEventService.recordSafely(
                    userId,
                    MarketingConstants.TASK_CONTINUOUS_SIGN_7,
                    "continuous-sign-7",
                    7
            );
        }
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String key = signKey(userId, now.toLocalDate());
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null || result.get(0) == 0) {
            return Result.ok(0);
        }

        long bits = result.get(0);
        int count = 0;
        while ((bits & 1) == 1) {
            count++;
            bits >>>= 1;
        }
        return Result.ok(count);
    }

    private boolean hasContinuousSigns(Long userId, LocalDate endDate, int days) {
        for (int i = 0; i < days; i++) {
            LocalDate date = endDate.minusDays(i);
            Boolean signed = stringRedisTemplate.opsForValue().getBit(
                    signKey(userId, date), date.getDayOfMonth() - 1
            );
            if (!Boolean.TRUE.equals(signed)) {
                return false;
            }
        }
        return true;
    }

    private String signKey(Long userId, LocalDate date) {
        return USER_SIGN_KEY + userId + date.format(SIGN_MONTH_FORMATTER);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
