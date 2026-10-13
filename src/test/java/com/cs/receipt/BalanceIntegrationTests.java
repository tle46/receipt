package com.cs.receipt;

import com.cs.receipt.model.*;
import com.cs.receipt.repository.*;
import com.cs.receipt.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class BalanceIntegrationTests {
    @Autowired BalanceService balances;
    @Autowired ReceiptService service;
    @Autowired UserRepository users;
    @Autowired ReceiptRepository receipts;
    @Autowired ReceiptParticipantRepository participants;

    User user(String name) { User u = new User(); u.setUsername(name); return users.save(u); }
    Receipt receipt(User owner, User debtor, String amount, String currency, ReceiptStatus status) {
        Receipt r = new Receipt(); r.setOwner(owner); r.setCurrency(currency); r.setStatus(status);
        r.setSubtotal(new BigDecimal(amount)); r.setTotal(new BigDecimal(amount));
        r.setDiscount(BigDecimal.ZERO); r.setTax(BigDecimal.ZERO); r.setFee(BigDecimal.ZERO);
        ReceiptParticipant own = new ReceiptParticipant(); own.setReceipt(r); own.setUser(owner); own.setSettled(true);
        ReceiptParticipant p = new ReceiptParticipant(); p.setReceipt(r); p.setUser(debtor); p.setFinalOwedAmount(new BigDecimal(amount));
        r.getParticipants().add(own); r.getParticipants().add(p); return receipts.saveAndFlush(r);
    }
    @Test void grossOffsetsBulkSettlementAndCurrencies() {
        User me = user("balance_me"), bob = user("balance_bob"), sam = user("balance_sam");
        Receipt a = receipt(me, bob, "7.00", "USD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "3.00", "USD", ReceiptStatus.FINALIZED);
        receipt(bob, me, "6.00", "USD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "8.00", "CAD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "99.00", "USD", ReceiptStatus.DRAFT);
        receipt(me, bob, "99.00", "USD", ReceiptStatus.SETTLED);
        ReceiptParticipant other = new ReceiptParticipant(); other.setReceipt(a); other.setUser(sam);
        other.setFinalOwedAmount(new BigDecimal("2.00")); a.getParticipants().add(other); participants.saveAndFlush(other);
        var usd = balances.getBalances(me.getId()).currencies().get(1);
        assertThat(usd.totalOwed()).isEqualByComparingTo("12.00");
        assertThat(usd.totalOwing()).isEqualByComparingTo("6.00");
        var bobRow = usd.people().stream().filter(p -> p.userId().equals(bob.getId())).findFirst().orElseThrow();
        assertThat(bobRow.netBalance()).isEqualByComparingTo("4.00");
        var result = balances.settlePerson(me.getId(), bob.getId());
        assertThat(result.affectedReceiptIds()).hasSize(3);
        assertThat(result.currencies()).hasSize(2);
        assertThat(a.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        assertThatThrownBy(() -> service.reopenReceipt(a.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteReceipt(a.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        var after = balances.getBalances(me.getId()).currencies().getFirst();
        assertThat(after.currency()).isEqualTo("USD");
        assertThat(after.totalOwed()).isEqualByComparingTo("2.00");
        assertThat(after.totalOwing()).isEqualByComparingTo("6.00");
        assertThat(balances.settlePerson(me.getId(), bob.getId()).affectedReceiptIds()).isEmpty();
        service.settleParticipant(a.getId(), other.getId(), me.getId());
        assertThat(a.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        service.settleParticipant(a.getId(), other.getId(), me.getId());
        assertThatThrownBy(() -> service.settleParticipant(a.getId(), other.getId(), bob.getId()))
                .isInstanceOf(com.cs.receipt.exception.ForbiddenOperationException.class);
    }
    @Test void zeroNetStillHasOutstandingDebtAndEmptyBalance() {
        User me = user("zero_me"), bob = user("zero_bob");
        assertThat(balances.getBalances(me.getId()).currencies()).isEmpty();
        receipt(me, bob, "6.00", "USD", ReceiptStatus.FINALIZED);
        receipt(bob, me, "6.00", "USD", ReceiptStatus.FINALIZED);
        var c = balances.getBalances(me.getId()).currencies().getFirst();
        assertThat(c.netBalance()).isZero(); assertThat(c.people()).hasSize(1);
        assertThat(c.totalOwed()).isEqualByComparingTo("6");
        assertThatThrownBy(() -> balances.settlePerson(me.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
    }
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Test void balanceEndpointsAuthorizeActorAndPreserveJsonContract() throws Exception {
        User me = user("api_balance_me"), bob = user("api_balance_bob");
        Receipt r = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var actor = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(me.getId().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/" + me.getId() + "/balances").with(actor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.currencies[0].totalOwed").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.currencies[0].people[0].netBalance").value(10));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/" + bob.getId() + "/balances").with(actor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/" + bob.getId() + "/balances/people/" + me.getId() + "/settle").with(actor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/" + me.getId() + "/balances/people/" + bob.getId() + "/settle").with(actor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.currencies[0].settledAmount").value(10));
        assertThat(r.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
    }
    @Test void unsettlementRestoresDebtsAndPreservesNamesAndOtherReceipts() {
        User me = user("undo_me"), bob = user("undo_bob"); bob.setDisplayName("Robert");
        Receipt r = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        Receipt other = receipt(me, bob, "3.00", "USD", ReceiptStatus.FINALIZED);
        Receipt draft = receipt(me, bob, "5.00", "USD", ReceiptStatus.DRAFT);
        balances.settlePerson(me.getId(), bob.getId());
        assertThat(balances.getBalances(me.getId()).currencies()).isEmpty();
        service.unsettleReceipt(r.getId(), me.getId());
        assertThat(r.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        assertThat(other.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        assertThat(balances.getBalances(me.getId()).currencies().getFirst().totalOwed()).isEqualByComparingTo("10");
        assertThat(balances.getBalances(bob.getId()).currencies().getFirst().totalOwing()).isEqualByComparingTo("10");
        service.unsettleReceipt(r.getId(), me.getId());
        var detail = service.getReceipt(r.getId(), bob.getId());
        var participant = detail.participants().stream().filter(p -> p.userId().equals(bob.getId())).findFirst().orElseThrow();
        assertThat(participant.displayName()).isEqualTo("Robert");
        assertThat(participant.username()).isEqualTo("undo_bob");
        assertThat(participant.settled()).isFalse();
        var split = com.cs.receipt.dto.ReceiptSplitResponse.from(r);
        assertThat(split.participants()).extracting(com.cs.receipt.dto.ReceiptSplitResponse.ParticipantSplit::displayName)
                .contains("Robert");
        assertThatThrownBy(() -> service.unsettleReceipt(r.getId(), bob.getId()))
                .isInstanceOf(com.cs.receipt.exception.ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.unsettleReceipt(draft.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        service.reopenReceipt(r.getId(), me.getId());
        assertThat(r.getStatus()).isEqualTo(ReceiptStatus.DRAFT);
    }

    @Test void unsettlementReversesPartialPaymentsButKeepsZeroAndOwnerSettled() {
        User me = user("partial_undo_me"), bob = user("partial_undo_bob"), sam = user("partial_undo_sam");
        Receipt r = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        ReceiptParticipant samShare = new ReceiptParticipant(); samShare.setReceipt(r); samShare.setUser(sam);
        samShare.setFinalOwedAmount(new BigDecimal("5.00")); r.getParticipants().add(samShare); participants.saveAndFlush(samShare);
        service.settleParticipant(r.getId(), r.getParticipants().get(1).getId(), me.getId());
        assertThat(r.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        service.unsettleReceipt(r.getId(), me.getId());
        assertThat(r.getParticipants().getFirst().isSettled()).isTrue();
        assertThat(balances.getBalances(me.getId()).currencies().getFirst().totalOwed()).isEqualByComparingTo("15");
        Receipt zero = receipt(me, bob, "0.00", "USD", ReceiptStatus.SETTLED);
        service.unsettleReceipt(zero.getId(), me.getId());
        assertThat(zero.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        assertThat(zero.getParticipants()).allMatch(ReceiptParticipant::isSettled);
    }
}
