package com.yuutara.illustrationarchive.repository;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Builds the gallery mapper for tests that wire repositories by hand instead of starting Spring Boot.
 * Like the auto-configuration it uses Spring-managed transactions on the given DataSource, and mirrors
 * {@code mybatis.configuration.local-cache-scope=statement} from application.properties.
 */
public final class GalleryMapperSupport {

	private GalleryMapperSupport() {
	}

	public static IllustrationGalleryMapper mapper(DataSource dataSource) {
		Configuration configuration = new Configuration();
		configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
		SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
		factory.setDataSource(dataSource);
		factory.setConfiguration(configuration);
		try {
			SqlSessionFactory sessions = factory.getObject();
			// Also loads IllustrationGalleryMapper.xml from the same classpath package.
			sessions.getConfiguration().addMapper(IllustrationGalleryMapper.class);
			return new SqlSessionTemplate(sessions).getMapper(IllustrationGalleryMapper.class);
		} catch (Exception e) {
			throw new IllegalStateException("Cannot build the gallery mapper.", e);
		}
	}

	public static IllustrationRepository repository(JdbcTemplate jdbc) {
		return new IllustrationRepository(jdbc, mapper(jdbc.getDataSource()));
	}
}
