package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.desktop.DesktopServer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** Gallery read contract on H2 with the desktop compatibility flags and the H2 migrations; always runs. */
class IllustrationGalleryReadH2Test extends IllustrationGalleryReadContract {

	private DataSource dataSource;

	@Override
	protected DataSource openDatabase() {
		dataSource = new DriverManagerDataSource("jdbc:h2:mem:gallery-" + UUID.randomUUID()
				+ DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1", "sa", "");
		migrate(dataSource, "classpath:db/migration-h2");
		return dataSource;
	}

	@Override
	protected void closeDatabase() {
		// SHUTDOWN can fail on its own statement once the database is gone; emptying it releases the memory.
		new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
	}
}
