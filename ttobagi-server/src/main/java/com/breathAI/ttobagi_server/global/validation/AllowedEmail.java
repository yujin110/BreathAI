package com.breathAI.ttobagi_server.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 가입·로그인에 쓸 수 있는 이메일: 공사 메일(@seoulmetro.co.kr)이거나 서버 설정에 따로 허용한 주소
@Documented
@Constraint(validatedBy = AllowedEmailValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedEmail {

    String message() default "이메일 도메인이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
