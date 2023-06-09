/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.gateway.rest.header.statement;

import org.apache.flink.runtime.rest.HttpMethodWrapper;
import org.apache.flink.table.gateway.rest.header.SqlGatewayMessageHeaders;
import org.apache.flink.table.gateway.rest.message.session.SessionHandleIdPathParameter;
import org.apache.flink.table.gateway.rest.message.session.SessionMessageParameters;
import org.apache.flink.table.gateway.rest.message.statement.ExecuteStatementRequestBody;
import org.apache.flink.table.gateway.rest.message.statement.ExecuteStatementResponseBody;

import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpResponseStatus;

/** Message headers for execute a statement. */
public class ExecuteStatementHeaders
        implements SqlGatewayMessageHeaders<
                ExecuteStatementRequestBody,
                ExecuteStatementResponseBody,
                SessionMessageParameters> {

    private static final ExecuteStatementHeaders INSTANCE = new ExecuteStatementHeaders();

    private static final String URL =
            "/sessions/:" + SessionHandleIdPathParameter.KEY + "/statements";

    @Override
    public Class<ExecuteStatementResponseBody> getResponseClass() {
        return ExecuteStatementResponseBody.class;
    }

    @Override
    public HttpResponseStatus getResponseStatusCode() {
        return HttpResponseStatus.OK;
    }

    @Override
    public String getDescription() {
        return "Execute a statement.";
    }

    @Override
    public Class<ExecuteStatementRequestBody> getRequestClass() {
        return ExecuteStatementRequestBody.class;
    }

    @Override
    public SessionMessageParameters getUnresolvedMessageParameters() {
        return new SessionMessageParameters();
    }

    @Override
    public HttpMethodWrapper getHttpMethod() {
        return HttpMethodWrapper.POST;
    }

    @Override
    public String getTargetRestEndpointURL() {
        return URL;
    }

    public static ExecuteStatementHeaders getInstance() {
        return INSTANCE;
    }
}
