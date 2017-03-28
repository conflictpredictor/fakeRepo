/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.db.migration;

import com.google.common.annotations.VisibleForTesting;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.apache.commons.dbutils.DbUtils;
import org.picocontainer.Startable;
import org.sonar.api.platform.ServerUpgradeStatus;
import org.sonar.api.utils.log.Loggers;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DdlUtils;
import org.sonar.db.dialect.Dialect;
import org.sonar.db.dialect.H2;
import org.sonar.server.platform.db.migration.engine.MigrationEngine;

/**
 * FIXME fix this class to remove use of DdlUtils.createSchema
 */
public class AutoDbMigration implements Startable {
  private final ServerUpgradeStatus serverUpgradeStatus;
  private final DbClient dbClient;
  private final MigrationEngine migrationEngine;

  public AutoDbMigration(ServerUpgradeStatus serverUpgradeStatus, DbClient dbClient, MigrationEngine migrationEngine) {
    this.serverUpgradeStatus = serverUpgradeStatus;
    this.dbClient = dbClient;
    this.migrationEngine = migrationEngine;
  }

  @Override
  public void start() {
    if (!serverUpgradeStatus.isFreshInstall()) {
      return;
    }

    Loggers.get(getClass()).info("Automatically perform DB migration on fresh install");
    Dialect dialect = dbClient.getDatabase().getDialect();
    if (H2.ID.equals(dialect.getId())) {
      installH2();
    } else {
      migrationEngine.execute();
    }
  }

  @VisibleForTesting
  void installH2() {
    Connection connection = null;
    try (DbSession session = dbClient.openSession(false)) {
      connection = session.getConnection();
      createH2Schema(connection, dbClient.getDatabase().getDialect().getId());
    } finally {
      DbUtils.closeQuietly(connection);
    }
  }

  @VisibleForTesting
  protected void createH2Schema(Connection connection, String dialectId) {
    DdlUtils.createSchema(connection, dialectId, false);
    hackFixForProjectMeasureTreeQueries(connection);
  }

  /**
   * see SONAR-8586
   */
  private static void hackFixForProjectMeasureTreeQueries(Connection connection) {
    int metricId = 1;
    try (PreparedStatement preparedStatement = connection.prepareStatement("insert into PROJECT_MEASURES (METRIC_ID,COMPONENT_UUID,ANALYSIS_UUID) values (?,?,?);")) {
      for (int i = 1; i < 1000; i++) {
        preparedStatement.setInt(1, metricId);
        preparedStatement.setString(2, "foo_" + i);
        preparedStatement.setString(3, "bar_" + i);
        preparedStatement.addBatch();
        if (i % 250 == 0) {
          preparedStatement.executeBatch();
          connection.commit();
        }
      }
      preparedStatement.executeBatch();
      connection.commit();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to insert fake rows into table PROJECT_MEASURES", e);
    }
  }

  @Override
  public void stop() {
    // nothing to do
  }
}
