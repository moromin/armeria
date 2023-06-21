/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.grpc.kotlin

import com.linecorp.armeria.grpc.kotlin.context.Hello
import com.linecorp.armeria.grpc.kotlin.context.HelloServiceGrpcKt
import org.assertj.core.api.Assertions.assertThat

class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {

    override suspend fun hello(request: Hello.HelloRequest): Hello.HelloReply {
        val threadName = Thread.currentThread().name
        assertThat(threadName).startsWith("custom-kotlin-grpc-worker")
        return Hello.HelloReply.newBuilder()
            .setMessage("Executed in $threadName")
            .build()
    }
}