package com.openitsm.vo;

import java.util.UUID;

public record ProfileVO(UUID id, String name, String skillsDescription, long version) {}
