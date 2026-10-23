package com.openitsm.service;

import com.openitsm.vo.SimilarIssuesVO;
import java.util.UUID;

public interface SimilarIssueService {
    SimilarIssuesVO search(AuthContext context, UUID ticketId);
}
