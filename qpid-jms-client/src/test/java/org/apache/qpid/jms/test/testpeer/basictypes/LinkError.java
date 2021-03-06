/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.jms.test.testpeer.basictypes;


import org.apache.qpid.proton.amqp.Symbol;


/**
 * Generated by generate-types.xsl, which resides in this package.
 */
public class LinkError {

    public static final Symbol DETACH_FORCED = Symbol.valueOf("amqp:link:detach-forced");
    public static final Symbol TRANSFER_LIMIT_EXCEEDED = Symbol.valueOf("amqp:link:transfer-limit-exceeded");
    public static final Symbol MESSAGE_SIZE_EXCEEDED = Symbol.valueOf("amqp:link:message-size-exceeded");
    public static final Symbol REDIRECT = Symbol.valueOf("amqp:link:redirect");
    public static final Symbol STOLEN = Symbol.valueOf("amqp:link:stolen");

    private LinkError() {
      //No instances
    }
}

