package com.assessment.agentic_system.shortener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "url_id", nullable = false)
    private ShortUrl url;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    @Column(length = 512)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(ShortUrl url, Instant clickedAt, String referrer, String userAgent) {
        this.url = url;
        this.clickedAt = clickedAt;
        this.referrer = truncate(referrer);
        this.userAgent = truncate(userAgent);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }

    public Long getId() { return id; }
    public ShortUrl getUrl() { return url; }
    public Instant getClickedAt() { return clickedAt; }
    public String getReferrer() { return referrer; }
    public String getUserAgent() { return userAgent; }
}
