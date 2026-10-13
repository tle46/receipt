package com.cs.receipt;

import com.cs.receipt.dto.ReceiptSplitResponse;
import com.cs.receipt.exception.ForbiddenOperationException;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.model.ReceiptStatus;
import com.cs.receipt.model.User;
import com.cs.receipt.repository.ReceiptParticipantRepository;
import com.cs.receipt.repository.ReceiptRepository;
import com.cs.receipt.repository.UserRepository;
import com.cs.receipt.service.BalanceService;
import com.cs.receipt.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@Transactional
class BalanceIntegrationTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private BalanceService balances;

    @Autowired
    private ReceiptService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private ReceiptRepository receipts;

    @Autowired
    private ReceiptParticipantRepository participants;

    @Test
    void grossOffsetsBulkSettlementAndCurrencies() {
        User me = user("balance_me");
        User bob = user("balance_bob");
        User sam = user("balance_sam");
        Receipt sharedReceipt = receipt(me, bob, "7.00", "USD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "3.00", "USD", ReceiptStatus.FINALIZED);
        receipt(bob, me, "6.00", "USD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "8.00", "CAD", ReceiptStatus.FINALIZED);
        receipt(me, bob, "99.00", "USD", ReceiptStatus.DRAFT);
        receipt(me, bob, "99.00", "USD", ReceiptStatus.SETTLED);
        ReceiptParticipant other = new ReceiptParticipant();
        other.setReceipt(sharedReceipt);
        other.setUser(sam);
        other.setFinalOwedAmount(new BigDecimal("2.00"));
        sharedReceipt.getParticipants().add(other);
        sharedReceipt.setSubtotal(new BigDecimal("9.00"));
        sharedReceipt.setTotal(new BigDecimal("9.00"));
        participants.saveAndFlush(other);
        var usd = balances.getBalances(me.getId()).currencies().get(1);
        assertThat(usd.totalOwed()).isEqualByComparingTo("12.00");
        assertThat(usd.totalOwing()).isEqualByComparingTo("6.00");
        var bobRow = usd.people().stream().filter(person -> person.userId().equals(bob.getId())).findFirst().orElseThrow();
        assertThat(bobRow.netBalance()).isEqualByComparingTo("4.00");
        var result = balances.settlePerson(me.getId(), bob.getId());
        assertThat(result.affectedReceiptIds()).hasSize(3);
        assertThat(result.currencies()).hasSize(2);
        assertThat(sharedReceipt.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        assertThatThrownBy(() -> service.reopenReceipt(sharedReceipt.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteReceipt(sharedReceipt.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        var after = balances.getBalances(me.getId()).currencies().getFirst();
        assertThat(after.currency()).isEqualTo("USD");
        assertThat(after.totalOwed()).isEqualByComparingTo("2.00");
        assertThat(after.totalOwing()).isEqualByComparingTo("6.00");
        assertThat(balances.settlePerson(me.getId(), bob.getId()).affectedReceiptIds()).isEmpty();
        service.settleParticipant(sharedReceipt.getId(), other.getId(), me.getId());
        assertThat(sharedReceipt.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        service.settleParticipant(sharedReceipt.getId(), other.getId(), me.getId());
        assertThatThrownBy(() -> service.settleParticipant(sharedReceipt.getId(), other.getId(), bob.getId()))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void zeroNetStillHasOutstandingDebtAndEmptyBalance() {
        User me = user("zero_me");
        User bob = user("zero_bob");
        assertThat(balances.getBalances(me.getId()).currencies()).isEmpty();
        receipt(me, bob, "6.00", "USD", ReceiptStatus.FINALIZED);
        receipt(bob, me, "6.00", "USD", ReceiptStatus.FINALIZED);
        var currencyBalance = balances.getBalances(me.getId()).currencies().getFirst();
        assertThat(currencyBalance.netBalance()).isZero();
        assertThat(currencyBalance.people()).hasSize(1);
        assertThat(currencyBalance.totalOwed()).isEqualByComparingTo("6");
        assertThatThrownBy(() -> balances.settlePerson(me.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balanceEndpointsAuthorizeActorAndPreserveJsonContract() throws Exception {
        User me = user("api_balance_me");
        User bob = user("api_balance_bob");
        Receipt receipt = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        var mvc = webAppContextSetup(context)
                .apply(springSecurity()).build();
        var actor = jwt()
                .jwt(j -> j.subject(me.getId().toString()));
        mvc.perform(get("/api/users/" + me.getId() + "/balances").with(actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies[0].totalOwed").value(10))
                .andExpect(jsonPath("$.currencies[0].people[0].netBalance").value(10));
        mvc.perform(get("/api/users/" + bob.getId() + "/balances").with(actor))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/" + bob.getId() + "/balances/people/" + me.getId() + "/settle").with(actor))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/" + me.getId() + "/balances/people/" + bob.getId() + "/settle").with(actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies[0].settledAmount").value(10));
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
    }

    @Test
    void unsettlementRestoresDebtsAndPreservesNamesAndOtherReceipts() {
        User me = user("undo_me");
        User bob = user("undo_bob");
        bob.setDisplayName("Robert");
        Receipt receipt = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        Receipt other = receipt(me, bob, "3.00", "USD", ReceiptStatus.FINALIZED);
        Receipt draft = receipt(me, bob, "5.00", "USD", ReceiptStatus.DRAFT);
        balances.settlePerson(me.getId(), bob.getId());
        assertThat(balances.getBalances(me.getId()).currencies()).isEmpty();
        service.unsettleReceipt(receipt.getId(), me.getId());
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        assertThat(other.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        assertThat(balances.getBalances(me.getId()).currencies().getFirst().totalOwed()).isEqualByComparingTo("10");
        assertThat(balances.getBalances(bob.getId()).currencies().getFirst().totalOwing()).isEqualByComparingTo("10");
        service.unsettleReceipt(receipt.getId(), me.getId());
        var detail = service.getReceipt(receipt.getId(), bob.getId());
        var participant = detail.participants().stream().filter(person -> person.userId().equals(bob.getId())).findFirst().orElseThrow();
        assertThat(participant.displayName()).isEqualTo("Robert");
        assertThat(participant.username()).isEqualTo("undo_bob");
        assertThat(participant.settled()).isFalse();
        var split = ReceiptSplitResponse.from(receipt);
        assertThat(split.participants()).extracting(ReceiptSplitResponse.ParticipantSplit::displayName)
                .contains("Robert");
        assertThatThrownBy(() -> service.unsettleReceipt(receipt.getId(), bob.getId()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.unsettleReceipt(draft.getId(), me.getId())).isInstanceOf(IllegalArgumentException.class);
        service.reopenReceipt(receipt.getId(), me.getId());
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.DRAFT);
    }

    @Test
    void unsettlementReversesPartialPaymentsButKeepsZeroAndOwnerSettled() {
        User me = user("partial_undo_me");
        User bob = user("partial_undo_bob");
        User sam = user("partial_undo_sam");
        Receipt receipt = receipt(me, bob, "10.00", "USD", ReceiptStatus.FINALIZED);
        ReceiptParticipant samShare = new ReceiptParticipant();
        samShare.setReceipt(receipt);
        samShare.setUser(sam);
        samShare.setFinalOwedAmount(new BigDecimal("5.00"));
        receipt.getParticipants().add(samShare);
        receipt.setSubtotal(new BigDecimal("15.00"));
        receipt.setTotal(new BigDecimal("15.00"));
        participants.saveAndFlush(samShare);
        service.settleParticipant(receipt.getId(), receipt.getParticipants().get(1).getId(), me.getId());
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        service.unsettleReceipt(receipt.getId(), me.getId());
        assertThat(receipt.getParticipants().getFirst().isSettled()).isTrue();
        assertThat(balances.getBalances(me.getId()).currencies().getFirst().totalOwed()).isEqualByComparingTo("15");
        Receipt zero = receipt(me, bob, "0.00", "USD", ReceiptStatus.SETTLED);
        service.unsettleReceipt(zero.getId(), me.getId());
        assertThat(zero.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        assertThat(zero.getParticipants()).allMatch(ReceiptParticipant::isSettled);
    }

    private User user(String name) {
        User user = new User();
        user.setUsername(name);
        return users.save(user);
    }

    private Receipt receipt(User owner, User debtor, String amount, String currency, ReceiptStatus status) {
        Receipt receipt = new Receipt();
        receipt.setOwner(owner);
        receipt.setCurrency(currency);
        receipt.setStatus(status);
        receipt.setSubtotal(new BigDecimal(amount));
        receipt.setTotal(new BigDecimal(amount));
        receipt.setDiscount(BigDecimal.ZERO);
        receipt.setTax(BigDecimal.ZERO);
        receipt.setFee(BigDecimal.ZERO);
        ReceiptParticipant ownerParticipant = new ReceiptParticipant();
        ownerParticipant.setReceipt(receipt);
        ownerParticipant.setUser(owner);
        ownerParticipant.setSettled(status != ReceiptStatus.DRAFT);
        ReceiptParticipant debtorParticipant = new ReceiptParticipant();
        debtorParticipant.setReceipt(receipt);
        debtorParticipant.setUser(debtor);
        debtorParticipant.setFinalOwedAmount(new BigDecimal(amount));
        debtorParticipant.setSettled(status == ReceiptStatus.SETTLED);
        receipt.getParticipants().add(ownerParticipant);
        receipt.getParticipants().add(debtorParticipant);
        return receipts.saveAndFlush(receipt);
    }
}
