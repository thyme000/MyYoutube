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
}
