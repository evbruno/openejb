/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

TOMEE.ApplicationTabJndi = function () {
    "use strict";

    var channel = TOMEE.ApplicationChannel,
        container = $(TOMEE.ApplicationTemplates.getValue('application-tab-jndi', {}));

    channel.bind('ui-actions', 'window-F5-pressed', function () {
        triggerRefresh();
    });

    channel.bind('server-command-callback-success', 'GetJndi', function (data) {
        var table = $(TOMEE.ApplicationTemplates.getValue('application-tab-jndi-table', {
            jndi:data.output.jndi
        }));

        container.find('table').remove();
        container.append(table);
    });

    function triggerRefresh() {
        channel.send('ui-actions', 'reload-jndi-table', {});
    }

    return {
        getEl:function () {
            return container;
        }
    };
};