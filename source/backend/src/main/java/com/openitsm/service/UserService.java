package com.openitsm.service;

import com.openitsm.dto.InviteUserRequest;
import com.openitsm.dto.UpdateUserRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.AssigneeListVO;
import com.openitsm.vo.UserPageVO;
import java.util.UUID;

public interface UserService {
    String role(String value);
    UserPageVO list(int page, String query, String role, String status);
    AssigneeListVO assignees();
    ActionVO invite(AuthContext context, InviteUserRequest body, UUID requestId);
    ActionVO change(AuthContext context, UUID id, String action, UpdateUserRequest patch, String reason,
        UUID requestId);
}
