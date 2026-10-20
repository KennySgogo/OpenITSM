package com.openitsm.service;

import com.openitsm.dto.UpdateProfileRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.ProfileVO;

public interface ProfileService {
    ProfileVO detail(AuthContext context);
    ActionVO update(AuthContext context, UpdateProfileRequest body, String version, String key);
}
