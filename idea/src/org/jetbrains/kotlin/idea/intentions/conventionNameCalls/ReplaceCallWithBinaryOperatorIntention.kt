/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions.conventionNameCalls

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.idea.intentions.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.isReallySuccess
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.expressions.OperatorConventions

class ReplaceCallWithBinaryOperatorIntention : SelfTargetingRangeIntention<KtDotQualifiedExpression>(KtDotQualifiedExpression::class.java, "Replace call with binary operator"), HighPriorityAction {
    override fun applicabilityRange(element: KtDotQualifiedExpression): TextRange? {
        val operation = operation(element.calleeName) ?: return null

        val resolvedCall = element.toResolvedCall(BodyResolveMode.PARTIAL) ?: return null
        if (!resolvedCall.isReallySuccess()) return null
        if (resolvedCall.call.typeArgumentList != null) return null
        val argument = resolvedCall.call.valueArguments.singleOrNull() ?: return null
        if ((resolvedCall.getArgumentMapping(argument) as ArgumentMatch).valueParameter.index != 0) return null

        if (!element.isReceiverExpressionWithValue()) return null

        text = "Replace with '$operation' operator"
        return element.callExpression!!.calleeExpression!!.textRange
    }

    override fun applyTo(element: KtDotQualifiedExpression, editor: Editor?) {
        val operation = operation(element.calleeName)!!
        val argument = element.callExpression!!.valueArguments.single().getArgumentExpression()!!
        val receiver = element.receiverExpression

        element.replace(KtPsiFactory(element).createExpressionByPattern("$0 $operation $1", receiver, argument))
    }

    private fun operation(functionName: String?): String? {
        if (functionName == null) return null
        return OperatorConventions.BINARY_OPERATION_NAMES.inverse()[Name.identifier(functionName)]?.value
    }
}
