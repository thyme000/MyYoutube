package cn.diaojiahong.myyoutube.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class ContentTestDatabase {
    private ContentTestDatabase() {}
    static DriverManagerDataSource create(String h2Name) {
        if (!Boolean.getBoolean("content.integration"))
            return new DriverManagerDataSource("jdbc:h2:mem:"+h2Name+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/content_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","root","content-it-only");
        if (new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM isolated_test_environment WHERE id=1",Integer.class) != 1)
            throw new IllegalStateException("Missing isolated Docker environment marker");
        return ds;
    }
    static void drop(JdbcTemplate jdbc,String... tables) {
        for(String table:tables) jdbc.execute("DROP TABLE IF EXISTS "+table);
    }
    static void outbox(JdbcTemplate jdbc) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS pipeline_event_outbox");
        String schema = java.nio.file.Files.readString(java.nio.file.Path.of("../../resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS pipeline_event_outbox (");
        String sql = schema.substring(start, schema.indexOf(';', start) + 1);
        if (!Boolean.getBoolean("content.integration")) sql = sql.replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB", "");
        jdbc.execute(sql);
    }
}
