package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CreditBalanceControllerTests {

  @Mock private CreditBalanceRepository creditBalanceRepository;

  @Mock private PolicyService policyService;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CreditBalanceController(creditBalanceRepository, policyService))
            .build();
  }

  @Test
  void anonymousRequestCannotReadCreditBalance() throws Exception {
    var response = mockMvc.perform(get("/api/v1/me/credits/balance")).andReturn().getResponse();

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"));
    verifyNoInteractions(policyService, creditBalanceRepository);
  }

  @Test
  void currentPolicyAcceptanceIsRequiredBeforeReadingBalance() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);

    var response =
        mockMvc
            .perform(get("/api/v1/me/credits/balance").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(creditBalanceRepository);
  }

  @Test
  void balanceResponseIsPrivateAndContainsOnlyAvailableCreditTotals() throws Exception {
    Instant asOf = Instant.parse("2026-11-15T12:00:00Z");
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(creditBalanceRepository.findAvailable(actorId))
        .thenReturn(
            Optional.of(
                new CreditBalanceRepository.CreditBalance(
                    BigInteger.valueOf(100),
                    BigInteger.valueOf(5),
                    BigInteger.valueOf(105),
                    asOf)));

    var response =
        mockMvc
            .perform(get("/api/v1/me/credits/balance").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    String body = response.getContentAsString();
    assertTrue(body.contains("\"paidCredits\":100"));
    assertTrue(body.contains("\"bonusCredits\":5"));
    assertTrue(body.contains("\"totalCredits\":105"));
    assertTrue(body.contains("2026-11-15T12:00:00Z"));
    assertTrue(!body.contains(actorId.toString()));
    verify(creditBalanceRepository).findAvailable(actorId);
  }

  @Test
  void unavailablePolicyStoreAndBalanceStoreReturnSafeServiceErrors() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.NOT_CONFIGURED);
    var policyUnavailable =
        mockMvc
            .perform(get("/api/v1/me/credits/balance").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(503, policyUnavailable.getStatus());
    assertTrue(policyUnavailable.getContentAsString().contains("POLICY_NOT_CONFIGURED"));

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(creditBalanceRepository.findAvailable(actorId))
        .thenThrow(new IllegalStateException("database details must not be exposed"));
    var balanceUnavailable =
        mockMvc
            .perform(get("/api/v1/me/credits/balance").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(503, balanceUnavailable.getStatus());
    assertTrue(balanceUnavailable.getContentAsString().contains("CREDIT_BALANCE_UNAVAILABLE"));
    assertTrue(!balanceUnavailable.getContentAsString().contains("database details"));
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
