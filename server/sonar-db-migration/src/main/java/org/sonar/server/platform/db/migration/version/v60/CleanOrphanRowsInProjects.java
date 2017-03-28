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
package org.sonar.server.platform.db.migration.version.v60;

import java.sql.SQLException;
import org.sonar.db.Database;
import org.sonar.server.platform.db.migration.step.MassUpdate;
import org.sonar.server.platform.db.migration.step.DataChange;

public class CleanOrphanRowsInProjects extends DataChange {

  public CleanOrphanRowsInProjects(Database db) {
    super(db);
  }

  @Override
  public void execute(Context context) throws SQLException {
    MassUpdate massUpdate = context.prepareMassUpdate();
    massUpdate.select("SELECT p.id from projects p where p.root_uuid is null" +
      " or (p.copy_resource_id is not null and p.copy_component_uuid is null)" +
      " or (p.person_id is not null and p.developer_uuid is null)");
    massUpdate.update("DELETE from projects WHERE id=?");
    massUpdate.rowPluralName("orphan projects");
    massUpdate.execute((row, update) -> {
      long projectId = row.getLong(1);
      update.setLong(1, projectId);
      return true;
    });
  }

}
