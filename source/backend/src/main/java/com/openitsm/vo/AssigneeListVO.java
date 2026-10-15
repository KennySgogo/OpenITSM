package com.openitsm.vo;

import java.time.Instant;
import java.util.List;

public record AssigneeListVO(List<AssigneeVO> items, Instant asOf) {}
