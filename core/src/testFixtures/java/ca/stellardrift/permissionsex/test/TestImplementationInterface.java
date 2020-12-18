/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.stellardrift.permissionsex.test;

import ca.stellardrift.permissionsex.BaseDirectoryScope;
import ca.stellardrift.permissionsex.ImplementationInterface;
import org.h2.jdbcx.JdbcDataSource;
import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.concurrent.Executor;

public class TestImplementationInterface implements ImplementationInterface {
    private final Path baseDirectory;
    private final Logger logger = LoggerFactory.getLogger("TestImpl");

    public TestImplementationInterface(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public Path baseDirectory(BaseDirectoryScope scope) {
        return baseDirectory;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public DataSource dataSourceForUrl(String url) {
        if (url.startsWith("jdbc:h2")) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            return ds;
        } else if (url.startsWith("jdbc:mysql")) {
            return new MariaDbDataSource(url);
        } else if (url.startsWith("jdbc:postgresql")) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(url);
            return ds;
        }
        throw new IllegalArgumentException("Unsupported database implementation!");
    }

    /**
     * Get an executor to run tasks asynchronously on.
     *
     * @return The async executor
     */
    @Override
    public Executor asyncExecutor() {
        return Runnable::run;
    }

    @Override
    public String getVersion() {
        return "test";
    }
}

