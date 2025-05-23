/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.util.lang;

import java.util.function.Consumer;

/**
 * Represents an operation that accepts a single input argument and returns
 * no result. Unlike most other functional interfaces,
 * {@code ConsumerX} is expected to operate via side-effects.
 * <p>
 * Also it is able to throw {@link Exception} unlike {@link Consumer}.
 *
 * @param <T> The type of the input to the operation.
 */
@FunctionalInterface
public interface ConsumerX<T> {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument.
     */
    public void accept(T t) throws Exception;
}
