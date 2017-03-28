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
package org.sonarqube.ws.client.project;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.MAX_PAGE_SIZE;

public class SearchWsRequest {

  private final String organization;
  private final String query;
  private final List<String> qualifiers;
  private final Integer page;
  private final Integer pageSize;

  public SearchWsRequest(Builder builder) {
    this.organization = builder.organization;
    this.query = builder.query;
    this.qualifiers = builder.qualifiers;
    this.page = builder.page;
    this.pageSize = builder.pageSize;
  }

  @CheckForNull
  public String getOrganization() {
    return organization;
  }

  public List<String> getQualifiers() {
    return qualifiers;
  }

  @CheckForNull
  public Integer getPage() {
    return page;
  }

  @CheckForNull
  public Integer getPageSize() {
    return pageSize;
  }

  @CheckForNull
  public String getQuery() {
    return query;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String organization;
    private List<String> qualifiers = new ArrayList<>();
    private Integer page;
    private Integer pageSize;
    private String query;

    public Builder setOrganization(@Nullable String organization) {
      this.organization = organization;
      return this;
    }

    public Builder setQualifiers(List<String> qualifiers) {
      this.qualifiers = requireNonNull(qualifiers, "Qualifiers cannot be null");
      return this;
    }

    public Builder setPage(@Nullable Integer page) {
      this.page = page;
      return this;
    }

    public Builder setPageSize(@Nullable Integer pageSize) {
      this.pageSize = pageSize;
      return this;
    }

    public Builder setQuery(@Nullable String query) {
      this.query = query;
      return this;
    }

    public SearchWsRequest build() {
      checkArgument(pageSize == null || pageSize <= MAX_PAGE_SIZE, "Page size must not be greater than %s", MAX_PAGE_SIZE);
      return new SearchWsRequest(this);
    }
  }

}
