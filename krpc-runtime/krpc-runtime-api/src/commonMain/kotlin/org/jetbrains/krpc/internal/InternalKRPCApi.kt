/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.krpc.internal

@RequiresOptIn(
    message = "This is internal kRPC api that is subject to change and should not be used",
    level = RequiresOptIn.Level.ERROR,
)
annotation class InternalKRPCApi