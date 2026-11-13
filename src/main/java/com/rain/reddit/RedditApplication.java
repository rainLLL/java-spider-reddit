package com.rain.reddit;

import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.service.CrawlService;
import com.rain.reddit.service.LogFileRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CrawlerProperties.class)
public class RedditApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedditApplication.class);
    private final CrawlService crawlService;

    public RedditApplication(CrawlService crawlService) {
        this.crawlService = crawlService;
    }

    public static void main(String[] args) {
        SpringApplication.run(RedditApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            LogFileRetention.cleanOldLogs();
            crawlService.crawl();
        } catch (Exception exception) {
            log.error("Crawler terminated unexpectedly", exception);
            throw new IllegalStateException("Crawler failed; see the latest file in logs/", exception);
        }
    }
}
