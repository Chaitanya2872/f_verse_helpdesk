package com.erp.ticketing.kb.repository;

import com.erp.ticketing.kb.model.KbArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbArticleRepository extends JpaRepository<KbArticle, Long>, JpaSpecificationExecutor<KbArticle> {

    @Modifying
    @Query("UPDATE KbArticle a SET a.viewCount = a.viewCount + 1 WHERE a.id = :id")
    void incrementViewCount(@Param("id") Long id);
}
