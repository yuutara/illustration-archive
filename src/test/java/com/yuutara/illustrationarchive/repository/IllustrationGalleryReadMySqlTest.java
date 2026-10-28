package com.yuutara.illustrationarchive.repository;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gallery read contract on real MySQL. Explicit opt-in only: the URL must name a dedicated, not yet
 * existing schema {@code illustration_archive_gallery_test_<digits>}. The test drops only a schema it
 * created itself; if the name already exists, CREATE fails and the existing schema is left untouched.
 * JUnit runs {@code @AfterAll} even when {@code @BeforeAll} fails, so a failed seed is cleaned up too.
 */
@EnabledIfEnvironmentVariable(named = "GALLERY_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class IllustrationGalleryReadMySqlTest extends IllustrationGalleryReadContract {

	private DisposableSchema schema;

	@Override
	protected DataSource openDatabase() {
		String url = System.getenv("GALLERY_MYSQL_TEST_URL");
		String username = System.getenv("GALLERY_MYSQL_TEST_USERNAME");
		String password = System.getenv("GALLERY_MYSQL_TEST_PASSWORD");
		var match = Pattern.compile("^jdbc:mysql://[^/]+/(illustration_archive_gallery_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(match.matches(), "Only a dedicated gallery test schema is allowed.");
		String name = match.group(1);
		var server = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + name, "/"), username, password));
		schema = new DisposableSchema(server, name);
		return schema.createThen(() -> {
			DataSource dataSource = new DriverManagerDataSource(url, username, password);
			migrate(dataSource, "classpath:db/migration");
			return dataSource;
		});
	}

	@Override
	protected void closeDatabase() {
		if (schema != null) schema.dropIfCreated();
	}
}
