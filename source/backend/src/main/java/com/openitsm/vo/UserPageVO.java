package com.openitsm.vo;

import java.util.List;
public record UserPageVO(List<UserVO> items, int page, int pageSize, long total) {}
