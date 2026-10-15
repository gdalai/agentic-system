package com.assessment.agentic_system.shortener;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByUrl(ShortUrl url);

    @Query("select max(c.clickedAt) from ClickEvent c where c.url = :url")
    Optional<Instant> findLastClickedAt(@Param("url") ShortUrl url);
}
