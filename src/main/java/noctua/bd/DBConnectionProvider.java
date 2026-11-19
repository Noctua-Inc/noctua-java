package noctua.bd;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

public class DBConnectionProvider {

    private final DataSource dataSource;



    public DBConnectionProvider() {

        Dotenv dotenv = Dotenv.load();

        BasicDataSource basicDataSource = new BasicDataSource();
        basicDataSource.setUrl("jdbc:mysql://localhost:3306/noctua_bd");
        basicDataSource.setUsername(dotenv.get("BD_USER"));
        basicDataSource.setPassword(dotenv.get("BD_SENHA"));

        this.dataSource = basicDataSource;
    }

    public JdbcTemplate getConnection() {
        return new JdbcTemplate(dataSource);
    }
}