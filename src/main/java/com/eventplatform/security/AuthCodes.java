package com.eventplatform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

@Service
public class AuthCodes {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Environment environment;
    private final String mode, endpoint, apiKey;
    private final SecureRandom random = new SecureRandom();
    private final DefaultRedisScript<Long> limit = script("rate-limit.lua");
    private final DefaultRedisScript<Long> verify = script("auth-code.lua");
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
    public AuthCodes(StringRedisTemplate redis,ObjectMapper json,Environment environment,
            @Value("${app.sms.mode:disabled}") String mode,
            @Value("${app.sms.endpoint:}") String endpoint,
            @Value("${app.sms.api-key:}") String apiKey) {
        this.redis=redis; this.json=json; this.environment=environment; this.mode=mode; this.endpoint=endpoint; this.apiKey=apiKey;
    }
    public void limit(String key,int max,int seconds) {
        if (!Long.valueOf(1).equals(redis.execute(limit,List.of(key),String.valueOf(max),String.valueOf(seconds))))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many requests; try again later");
    }
    public Result send(String phone) {
        boolean local = "local".equals(mode) && Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (!local && !"webhook".equals(mode)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SMS delivery is not configured");
        limit("auth:send:{"+phone+"}",1,60);
        limit("auth:daily:{"+phone+"}",10,86400);
        String code = String.format("%06d",random.nextInt(1_000_000));
        String key = "auth:code:{"+phone+"}";
        redis.opsForValue().set(key,code,Duration.ofMinutes(2));
        if (local) return Result.ok(Map.of("developmentCode",code));
        try {
            URI uri=URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || apiKey.isBlank()) throw new IllegalStateException("HTTPS SMS gateway required");
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header("Authorization","Bearer "+apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("phone",phone,"code",code,"ttlSeconds",120)))).build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request,HttpResponse.BodyHandlers.discarding());
            if (response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("SMS delivery rejected");
            return Result.ok();
        } catch (Exception e) {
            redis.delete(key);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SMS delivery failed; try again later");
        }
    }
    public boolean consume(String phone,String code) {
        if (code==null || !code.matches("[0-9]{6}")) return false;
        Long result=redis.execute(verify,List.of("auth:code:{"+phone+"}","auth:attempts:{"+phone+"}"),code);
        if (Long.valueOf(-1).equals(result)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many verification attempts");
        return Long.valueOf(1).equals(result);
    }
}
