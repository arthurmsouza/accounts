package com.r3.corda.lib.accounts.workflows.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.internal.flows.AccountSearchStatus
import com.r3.corda.lib.ci.ProvideKeyFlow
import com.r3.corda.lib.ci.RequestKeyFlow
import com.r3.corda.lib.ci.VerifyAndAddKey
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.internal.hash
import net.corda.core.utilities.unwrap
import net.corda.node.services.keys.PublicKeyHashToExternalId
import java.util.*

class RequestKeyForAccountFlow(
        private val accountInfo: AccountInfo,
        private val hostSession: FlowSession
) : FlowLogic<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        // If the account host is the node running this flow then generate a new CI locally and return it. Otherwise call out
        // to the remote host and ask THEM to generate a new CI and send it back.
        val newKey = if (accountInfo.host == ourIdentity) {
            subFlow(VerifyAndAddKey(ourIdentity, serviceHub.keyManagementService.freshKey(accountInfo.identifier.id))).publicKey
        } else {
            val accountSearchStatus = hostSession.sendAndReceive<AccountSearchStatus>(accountInfo.identifier.id).unwrap { it }
            when (accountSearchStatus) {
                AccountSearchStatus.NOT_FOUND -> {
                    throw IllegalStateException("Account Host: ${accountInfo.host} for ${accountInfo.identifier} " +
                            "(${accountInfo.name}) responded with a not found status - contact them for assistance")
                }
                AccountSearchStatus.FOUND -> {
                    val keyFromRemoteHost = subFlow(RequestKeyFlow(hostSession, accountInfo.identifier.id)).publicKey
                    // Store a local mapping of the account ID to the public key we've just received from the host.
                    // This allows us to look up the account which the PublicKey is linked to in the future.
                    serviceHub.withEntityManager {
                        persist(PublicKeyHashToExternalId(
                                accountId = accountInfo.linearId.id,
                                publicKey = keyFromRemoteHost
                        ))
                    }
                    keyFromRemoteHost
                }
            }
        }
        return AnonymousParty(newKey)
    }
}

class SendKeyForAccountFlow(val otherSide: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val requestedAccountForKey = otherSide.receive(UUID::class.java).unwrap { it }
        val existingAccountInfo = accountService.accountInfo(requestedAccountForKey)
        if (existingAccountInfo == null) {
            otherSide.send(AccountSearchStatus.NOT_FOUND)
        } else {
            otherSide.send(AccountSearchStatus.FOUND)
            subFlow(ProvideKeyFlow(otherSide))
        }
    }
}

// Initiating flows which can be started via RPC or a service. Calling these as a sub-flow from an existing flow will
// result in a new session being created.
@InitiatingFlow
@StartableByRPC
@StartableByService
class RequestKeyForAccount(private val accountInfo: AccountInfo) : FlowLogic<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        val hostSession = initiateFlow(accountInfo.host)
        return subFlow(RequestKeyForAccountFlow(accountInfo, hostSession))
    }
}

@InitiatedBy(RequestKeyForAccount::class)
class SendKeyForAccount(val otherSide: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() = subFlow(SendKeyForAccountFlow(otherSide))
}