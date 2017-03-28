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
import React from 'react';
import DateInput from '../../../components/controls/DateInput';
import { translate } from '../../../helpers/l10n';

export default class ChangelogSearch extends React.Component {
  static propTypes = {
    fromDate: React.PropTypes.string,
    toDate: React.PropTypes.string,
    onFromDateChange: React.PropTypes.func.isRequired,
    onToDateChange: React.PropTypes.func.isRequired,
    onReset: React.PropTypes.func.isRequired
  };

  handleResetClick(e) {
    e.preventDefault();
    e.target.blur();
    this.props.onReset();
  }

  render() {
    return (
      <div className="display-inline-block" id="quality-profile-changelog-form">
        <DateInput
          name="since"
          value={this.props.fromDate}
          placeholder="From"
          onChange={this.props.onFromDateChange}
        />
        {' — '}
        <DateInput
          name="to"
          value={this.props.toDate}
          placeholder="To"
          onChange={this.props.onToDateChange}
        />
        <button className="spacer-left" onClick={this.handleResetClick.bind(this)}>
          {translate('reset_verb')}
        </button>
      </div>
    );
  }
}
