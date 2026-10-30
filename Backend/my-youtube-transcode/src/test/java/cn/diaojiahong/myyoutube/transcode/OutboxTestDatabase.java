package cn.diaojiahong.myyoutube.transcode;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;

final class OutboxTestDatabase {
    private OutboxTestDatabase() {
    }

    static void reset(JdbcTemplate jdbc) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS pipeline_event_outbox");
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS pipeline_event_outbox (");
        String sql = schema.substring(start, schema.indexOf(';', start) + 1);
        if (!Boolean.getBoolean("content.integration")) sql = sql.replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace(" ENGINE=InnoDB", "");
        jdbc.execute(sql);
    }
}
