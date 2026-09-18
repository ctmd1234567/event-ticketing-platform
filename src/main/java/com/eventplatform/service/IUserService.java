package com.eventplatform.service;

import com.eventplatform.dto.LoginFormDTO;
import com.eventplatform.dto.Result;

public interface IUserService {
    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);
}
