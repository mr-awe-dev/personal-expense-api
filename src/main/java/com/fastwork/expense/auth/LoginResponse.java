package com.fastwork.expense.auth;

import com.fastwork.expense.user.MeResponse;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginResponse")
public record LoginResponse(MeResponse user) {}
