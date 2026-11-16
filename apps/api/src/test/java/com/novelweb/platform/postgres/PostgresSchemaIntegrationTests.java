package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.novelweb.identity.auth.GoogleAccountLinkRequiredException;
import com.novelweb.identity.auth.GoogleAuthAttemptService;
import com.novelweb.identity.auth.GoogleAuthBootstrapRateLimiter;
import com.novelweb.identity.auth.GoogleIdTokenVerifier;
import com.novelweb.identity.auth.GoogleIdentityService;
import com.novelweb.identity.auth.GoogleIdentityTestFixtures;
import com.novelweb.identity.auth.UnavailableGoogleIdentityException;
import com.novelweb.identity.session.LogoutAllConfirmationService;
import com.novelweb.identity.session.SessionCsrfTokenService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.LibraryCursorCodec;
import com.novelweb.library.LibraryRepository;
import com.novelweb.library.LibraryService;
import com.novelweb.library.LibraryUserStateRepository;
import com.novelweb.library.LibraryUserStateService;
import com.novelweb.library.WorkspaceRepository;
import com.novelweb.library.WorkspaceService;
import com.novelweb.library.imports.ImportParseService;
import com.novelweb.library.imports.ImportStagingCleanupService;
import com.novelweb.library.imports.ImportStagingRateLimiter;
import com.novelweb.payment.credit.ChapterUnlockService;
import com.novelweb.payment.credit.CreditBalanceRepository;
import com.novelweb.payment.credit.CreditHistoryRepository;
import com.novelweb.payment.credit.CreditHistoryService;
import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditPackCatalog;
import com.novelweb.payment.credit.CreditTopUpCheckoutService;
import com.novelweb.payment.credit.CreditTopUpOrderService;
import com.novelweb.payment.momo.MomoTestGatewayClient;
import com.novelweb.payment.momo.MomoTestIpnService;
import com.novelweb.payment.momo.MomoTestIpnTestSupport;
import com.novelweb.payment.momo.MomoTestIpnTestSupport.SignedNotification;
import com.novelweb.payment.momo.MomoTestOrderReconciliationService;
import com.novelweb.progression.cultivation.CultivationRepository;
import com.novelweb.progression.cultivation.CultivationService;
import com.novelweb.translation.glossary.GlossaryCatalogController;
import com.novelweb.translation.glossary.GlossaryImportApplyController;
import com.novelweb.translation.glossary.GlossaryImportApplyService;
import com.novelweb.translation.glossary.GlossaryImportParser;
import com.novelweb.translation.glossary.GlossaryMatcher;
import com.novelweb.translation.glossary.GlossaryRepository;
import com.novelweb.translation.glossary.GlossaryRevisionController;
import com.novelweb.translation.glossary.GlossaryRevisionWriteRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
@org.springframework.test.context.TestPropertySource(
    properties = {
      "novel.auth.google.rate-limit-refill-interval=24h",
      "novel.auth.google.rate-limit-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.auth.google.trusted-proxies=10.0.0.0/8",
      "novel.auth.google.web-client-id=novel-web-integration-test.apps.googleusercontent.com",
      "novel.auth.google.credential-callback-uri=https://api.example.test/api/v1/auth/google/credential",
      "novel.security.csrf.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.features.cultivation-enabled=true",
      "novel.features.commerce-sandbox-enabled=true",
      "novel.library.cursor.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.payment.momo.test.enabled=true",
      "novel.payment.momo.test.environment=test",
      "novel.payment.momo.test.partner-code=MOMOTEST123",
      "novel.payment.momo.test.access-key=synthetic-ipn-access",
      "novel.payment.momo.test.secret-key=synthetic-ipn-secret-only",
      "novel.payment.momo.test.base-url=https://test-payment.momo.vn",
      "novel.payment.momo.test.ipn-url=https://novel.example/api/v1/payments/momo/ipn",
      "novel.payment.momo.test.redirect-url=https://novel.example/app/payment/result",
      "novel.imports.staging.root=${java.io.tmpdir}/novel-web-import-postgres-tests",
      "novel.imports.parser.poll-delay=PT1H",
      "novel.imports.staging.max-upload-bytes=1024",
      "novel.imports.staging.upload-target-ttl=10m",
      "novel.imports.staging.job-ttl=1h",
      "novel.imports.staging.cleanup-delay=1h",
      "novel.imports.staging.cleanup-batch-size=100",
      "novel.imports.staging.max-active-jobs-per-user=2",
      "novel.imports.staging.max-active-declared-bytes-per-user=1024",
      "novel.imports.staging.create-rate-limit-capacity=20",
      "novel.imports.staging.create-rate-limit-refill-interval=1h",
      "novel.imports.staging.upload-rate-limit-capacity=20",
      "novel.imports.staging.upload-rate-limit-refill-interval=1h",
      "novel.imports.staging.rate-limit-retention=24h",
      "novel.web.cors.allowed-origins=https://app.example.test"
    })
class PostgresSchemaIntegrationTests {

  private static final String BOOTSTRAP_RATE_LIMIT_HMAC_KEY =
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  private static final Set<String> EXPECTED_APPLICATION_TABLES =
      Set.of(
          "users",
          "security_role_assignments",
          "security_role_assignment_events",
          "security_role_assignment_idempotency",
          "external_identities",
          "sessions",
          "google_auth_attempts",
          "google_auth_bootstrap_rate_limits",
          "policy_acceptances",
          "policy_documents",
          "auth_events",
          "session_revoke_confirmations",
          "libraries",
          "works",
          "volumes",
          "chapters",
          "chapter_revisions",
          "segments",
          "assets",
          "tags",
          "work_tags",
          "workspaces",
          "workspace_members",
          "library_reading_states",
          "reader_progress",
          "reader_preferences",
          "reader_bookmarks",
          "reader_bookmark_idempotency",
          "reader_annotations",
          "cultivation_rank_catalogs",
          "cultivation_rank_catalog_entries",
          "cultivation_profiles",
          "cultivation_xp_ledger",
          "cultivation_daily_xp_aggregates",
          "cultivation_daily_active_time",
          "cultivation_reading_sessions",
          "cultivation_reading_session_events",
          "glossaries",
          "glossary_revisions",
          "glossary_terms",
          "glossary_aliases",
          "glossary_publication_events",
          "glossary_import_apply_idempotency",
          "glossary_import_apply_rows",
          "translation_jobs",
          "translation_units",
          "translation_unit_segments",
          "translation_attempts",
          "translation_candidates",
          "translation_candidate_segments",
          "translation_candidate_segment_edits",
          "translation_versions",
          "translation_outbox_events",
          "library_collections",
          "library_collection_works",
          "import_rights_statements",
          "import_rights_acceptances",
          "publication_rights_policy_documents",
          "publication_rights_policy_acceptances",
          "publication_rights_policy_acceptance_idempotency",
          "publication_scope_access_approvals",
          "publication_scope_access_revocations",
          "publication_requests",
          "publication_request_scope_snapshots",
          "publication_request_scope_claims",
          "publication_review_events",
          "import_jobs",
          "import_actor_rate_limits",
          "import_parse_jobs",
          "import_commits",
          "authoring_manuscript_creations",
          "authoring_chapter_creations",
          "authoring_chapter_revision_writes",
          "credit_ledger_entries",
          "credit_balance_projection",
          "credit_top_up_orders",
          "momo_test_ipn_events",
          "momo_test_reconciliation_events",
          "content_entitlements",
          "public_catalog_rate_limits");

  @Autowired private DataSource dataSource;

  @Autowired private GoogleAuthAttemptService googleAuthAttemptService;

  @Autowired private GoogleAuthBootstrapRateLimiter googleAuthBootstrapRateLimiter;

  @Autowired private GoogleIdentityService googleIdentityService;

  @Autowired private SessionService sessionService;

  @Autowired private SessionCsrfTokenService csrfTokenService;

  @Autowired private LogoutAllConfirmationService logoutAllConfirmationService;

  @Autowired private LibraryService libraryService;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private WorkspaceService workspaceService;

  @Autowired private LibraryUserStateService libraryUserStateService;

  @Autowired private CultivationRepository cultivationRepository;

  @Autowired private CreditLedgerService creditLedgerService;

  @Autowired private ChapterUnlockService chapterUnlockService;

  @Autowired private CreditBalanceRepository creditBalanceRepository;

  @Autowired private CreditHistoryService creditHistoryService;

  @Autowired private CreditTopUpOrderService creditTopUpOrderService;

  @Autowired private CreditPackCatalog creditPackCatalog;

  @MockitoBean private CreditTopUpCheckoutService creditTopUpCheckoutService;

  @Autowired private MomoTestIpnService momoTestIpnService;

  @Autowired private GlossaryRepository glossaryRepository;

  @Autowired private GlossaryRevisionWriteRepository glossaryRevisionWriteRepository;

  @Autowired private CultivationService cultivationService;

  @Autowired private com.novelweb.library.imports.ImportStagingFileStore importStagingFileStore;

  @Autowired private ImportStagingCleanupService importStagingCleanupService;

  @Autowired private ImportStagingRateLimiter importStagingRateLimiter;

  @Autowired private ImportParseService importParseService;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeAll
  void insertPolicyDocumentFixtures() throws SQLException {
    insertPolicyTestDocuments();
  }

  private void insertPolicyTestDocuments() throws SQLException {
    insertPolicyDocument(
        "TERMS", "terms-old", -3, "# Test terms v1\n\nSynthetic test document; not legal copy.");
    insertPolicyDocument(
        "TERMS",
        "terms-current-1",
        -2,
        "# Test terms v2\n\nSynthetic test document; not legal copy.");
    insertPolicyDocument(
        "TERMS",
        "terms-future-2",
        1,
        "# Future test terms\n\nSynthetic test document; not legal copy.");
    insertPolicyDocument(
        "PRIVACY_NOTICE",
        "privacy-old",
        -2,
        "# Test privacy v1\n\nSynthetic test document; not legal copy.");
    insertPolicyDocument(
        "PRIVACY_NOTICE",
        "privacy-current-1",
        -1,
        "# Test privacy v2\n\nSynthetic test document; not legal copy.");
  }

  @Test
  void creditBalanceIsActorScopedAndExcludesExpiredBonusTranches() throws Exception {
    UUID userId = createSessionTestUser("Credit Balance Owner");
    UUID otherUserId = createSessionTestUser("Other Credit Balance Owner");
    UUID paidSourceId = UUID.randomUUID();
    UUID currentBonusSourceId = UUID.randomUUID();
    UUID expiredBonusSourceId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
    Instant expiredAt = Instant.now().minus(1, ChronoUnit.DAYS);
    try {
      assertEquals(
          401,
          mockMvc.perform(get("/api/v1/me/credits/balance")).andReturn().getResponse().getStatus());
      try (Connection connection = dataSource.getConnection()) {
        insertCreditEntry(
            connection, userId, "TOP_UP", "PAID", 100, null, "PAYMENT_ORDER", paidSourceId);
        insertCreditEntry(
            connection,
            userId,
            "BONUS_GRANT",
            "BONUS",
            5,
            expiresAt,
            "OPERATOR",
            currentBonusSourceId);
        insertCreditEntry(
            connection,
            userId,
            "BONUS_GRANT",
            "BONUS",
            50,
            expiredAt,
            "OPERATOR",
            expiredBonusSourceId);
      }

      CreditBalanceRepository.CreditBalance balance =
          creditBalanceRepository.findAvailable(userId).orElseThrow();
      CreditBalanceRepository.CreditBalance otherBalance =
          creditBalanceRepository.findAvailable(otherUserId).orElseThrow();
      assertEquals(new java.math.BigInteger("100"), balance.paidCredits());
      assertEquals(new java.math.BigInteger("5"), balance.bonusCredits());
      assertEquals(new java.math.BigInteger("105"), balance.totalCredits());
      assertNotNull(balance.asOf());
      assertEquals(java.math.BigInteger.ZERO, otherBalance.totalCredits());

      addCurrentPolicyAcceptances(userId);
      SessionService.IssuedSession ownerSession = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession blockedSession =
          sessionService.create(otherUserId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      MvcResult ownerResponse =
          mockMvc.perform(get("/api/v1/me/credits/balance").cookie(ownerCookie)).andReturn();
      assertEquals(200, ownerResponse.getResponse().getStatus());
      assertTrue(
          ownerResponse.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      assertTrue(ownerResponse.getResponse().getContentAsString().contains("\"totalCredits\":105"));
      assertTrue(!ownerResponse.getResponse().getContentAsString().contains(userId.toString()));
      MvcResult blockedResponse =
          mockMvc.perform(get("/api/v1/me/credits/balance").cookie(blockedCookie)).andReturn();
      assertEquals(403, blockedResponse.getResponse().getStatus());
      assertTrue(
          blockedResponse
              .getResponse()
              .getContentAsString()
              .contains("POLICY_ACCEPTANCE_REQUIRED"));
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void liquibaseAppliesTheBaselineAndCreatesExpectedTables() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement changesetCount =
            connection.prepareStatement("SELECT COUNT(*) FROM databasechangelog");
        ResultSet changesets = changesetCount.executeQuery()) {
      changesets.next();
      assertEquals(147, changesets.getInt(1), "unexpected number of applied changesets");
    }

    Set<String> actualTables = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement tableQuery =
            connection.prepareStatement(
                """
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name NOT IN ('databasechangelog', 'databasechangeloglock')
                     """);
        ResultSet tables = tableQuery.executeQuery()) {
      while (tables.next()) {
        actualTables.add(tables.getString(1));
      }
    }
    assertEquals(EXPECTED_APPLICATION_TABLES, actualTables);
  }

  @Test
  void publicationRightsAcceptanceIsPolicyVersionBoundOwnerScopedAndAppendOnly() throws Exception {
    UUID ownerId = createSessionTestUser("Synthetic publication-rights policy owner");
    UUID approverId = createSessionTestUser("Synthetic publication-rights policy approver");
    String policyVersion = "synthetic-publication-rights-v1";
    String policyText = "Synthetic test-only publication-rights policy; not legal text.";
    byte[] policyDigest = sha256(policyText);
    UUID workId = null;
    UUID acceptanceId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      UUID authoredWorkId =
          libraryService
              .createPrivateAuthoredWork(
                  ownerId,
                  "Publication Rights Fixture",
                  "Synthetic Author",
                  "vi",
                  "pub-rights-fixture-0001")
              .id();
      workId = authoredWorkId;

      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO publication_rights_policy_documents
                          (version, effective_at, display_text, policy_sha256)
                      VALUES (?, clock_timestamp() - INTERVAL '1 second', ?, ?)
                      """)) {
        insert.setString(1, policyVersion);
        insert.setString(2, policyText);
        insert.setBytes(3, policyDigest);
        assertEquals(1, insert.executeUpdate());
      }

      SQLException draftAcceptance =
          assertThrows(
              SQLException.class,
              () ->
                  insertPublicationRightsPolicyAcceptance(
                      acceptanceId,
                      ownerId,
                      authoredWorkId,
                      idempotencyKey,
                      policyVersion,
                      policyDigest));
      assertEquals("23514", draftAcceptance.getSQLState());

      try (Connection connection = dataSource.getConnection();
          PreparedStatement approve =
              connection.prepareStatement(
                  """
                      UPDATE publication_rights_policy_documents
                         SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                       WHERE version = ? AND status = 'DRAFT'
                      """)) {
        approve.setObject(1, approverId);
        approve.setString(2, policyVersion);
        assertEquals(1, approve.executeUpdate());
      }

      insertPublicationRightsPolicyAcceptance(
          acceptanceId, ownerId, authoredWorkId, idempotencyKey, policyVersion, policyDigest);
      assertEquals(1, publicationRightsPolicyAcceptanceCount(ownerId, authoredWorkId));

      SQLException repeatedIdempotencyKey =
          assertThrows(
              SQLException.class,
              () ->
                  insertPublicationRightsPolicyAcceptance(
                      UUID.randomUUID(),
                      ownerId,
                      authoredWorkId,
                      idempotencyKey,
                      policyVersion,
                      policyDigest));
      assertEquals("23505", repeatedIdempotencyKey.getSQLState());

      SQLException repeatedPolicyAcceptance =
          assertThrows(
              SQLException.class,
              () ->
                  insertPublicationRightsPolicyAcceptance(
                      UUID.randomUUID(),
                      ownerId,
                      authoredWorkId,
                      UUID.randomUUID(),
                      policyVersion,
                      policyDigest));
      assertEquals("23505", repeatedPolicyAcceptance.getSQLState());

      SQLException foreignOwner =
          assertThrows(
              SQLException.class,
              () ->
                  insertPublicationRightsPolicyAcceptance(
                      UUID.randomUUID(),
                      approverId,
                      authoredWorkId,
                      UUID.randomUUID(),
                      policyVersion,
                      policyDigest));
      assertEquals("23503", foreignOwner.getSQLState());

      byte[] mismatchedDigest = policyDigest.clone();
      mismatchedDigest[0] = (byte) (mismatchedDigest[0] ^ 0x01);
      SQLException mismatchedPolicySnapshot =
          assertThrows(
              SQLException.class,
              () ->
                  insertPublicationRightsPolicyAcceptance(
                      UUID.randomUUID(),
                      ownerId,
                      authoredWorkId,
                      UUID.randomUUID(),
                      policyVersion,
                      mismatchedDigest));
      assertEquals("23514", mismatchedPolicySnapshot.getSQLState());

      SQLException changedApprovedPolicy =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE publication_rights_policy_documents SET display_text = 'changed'"
                                + " WHERE version = ?")) {
                  update.setString(1, policyVersion);
                  update.executeUpdate();
                }
              });
      assertEquals("55000", changedApprovedPolicy.getSQLState());

      SQLException rewrittenAcceptance =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE publication_rights_policy_acceptances SET source = 'web-import-flow'"
                                + " WHERE id = ?")) {
                  update.setObject(1, acceptanceId);
                  update.executeUpdate();
                }
              });
      assertEquals("55000", rewrittenAcceptance.getSQLState());

      SQLException deletedAcceptance =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement delete =
                        connection.prepareStatement(
                            "DELETE FROM publication_rights_policy_acceptances WHERE id = ?")) {
                  delete.setObject(1, acceptanceId);
                  delete.executeUpdate();
                }
              });
      assertEquals("55000", deletedAcceptance.getSQLState());

      SQLException truncatedAcceptances =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    java.sql.Statement truncate = connection.createStatement()) {
                  truncate.execute(
                      "TRUNCATE publication_review_events, publication_request_scope_claims,"
                          + " publication_request_scope_snapshots, publication_requests,"
                          + " publication_rights_policy_acceptance_idempotency,"
                          + " publication_rights_policy_acceptances");
                }
              });
      assertEquals("55000", truncatedAcceptances.getSQLState());

      SQLException deletedAuthoredWorkLink =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement delete =
                        connection.prepareStatement(
                            "DELETE FROM authoring_manuscript_creations WHERE work_id = ?")) {
                  delete.setObject(1, authoredWorkId);
                  delete.executeUpdate();
                }
              });
      assertEquals("23503", deletedAuthoredWorkLink.getSQLState());
    } finally {
      if (workId != null) {
        deletePublicationRightsPolicyAcceptanceFixture(workId);
        deleteBareAuthoredWorkData(ownerId, workId);
      }
      deletePublicationRightsPolicyFixture(policyVersion);
      deleteOwnerLibraries(ownerId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void publicationRightsPolicyHttpFlowIsFailClosedOwnerScopedAndRetrySafe() throws Exception {
    UUID ownerId = createSessionTestUser("Synthetic publication policy API owner");
    UUID outsiderId = createSessionTestUser("Synthetic publication policy API outsider");
    UUID approverId = createSessionTestUser("Synthetic publication policy API approver");
    UUID unacceptedAuthorId = createSessionTestUser("Synthetic publication policy API unaccepted");
    addCurrentPolicyAcceptances(ownerId);
    addCurrentPolicyAcceptances(outsiderId);
    addCurrentPolicyAcceptances(unacceptedAuthorId);
    String policyVersion = "publication-policy-api-" + UUID.randomUUID().toString().substring(0, 8);
    String malformedDigestVersion =
        "publication-policy-digest-" + UUID.randomUUID().toString().substring(0, 8);
    String policyText = "Synthetic test-only publication policy served to its author.";
    byte[] policyDigest = sha256(policyText);
    UUID ownerWorkId = null;
    UUID unacceptedWorkId = null;
    try {
      ownerWorkId =
          libraryService
              .createPrivateAuthoredWork(
                  ownerId,
                  "Publication Policy API Fixture",
                  "Synthetic Author",
                  "vi",
                  "pub-policy-api-owner-0001")
              .id();
      unacceptedWorkId =
          libraryService
              .createPrivateAuthoredWork(
                  unacceptedAuthorId,
                  "Unaccepted Policy Fixture",
                  "Synthetic Author",
                  "vi",
                  "pub-policy-api-unaccepted-001")
              .id();

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      SessionService.IssuedSession unacceptedSession =
          sessionService.create(unacceptedAuthorId).orElseThrow();
      jakarta.servlet.http.Cookie unacceptedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", unacceptedSession.cookieValue());

      MvcResult noPolicy =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(503, noPolicy.getResponse().getStatus());
      assertTrue(
          noPolicy
              .getResponse()
              .getContentAsString()
              .contains("PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED"));
      assertTrue(noPolicy.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      assertEquals(0, publicationRightsPolicyAcceptanceCount(ownerId, ownerWorkId));

      MvcResult foreignPolicyRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, foreignPolicyRead.getResponse().getStatus());
      assertTrue(
          foreignPolicyRead.getResponse().getContentAsString().contains("MANUSCRIPT_NOT_FOUND"));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO publication_rights_policy_documents
                          (version, effective_at, display_text, policy_sha256)
                      VALUES (?, clock_timestamp() - INTERVAL '1 second', ?, ?)
                      """)) {
        insert.setString(1, policyVersion);
        insert.setString(2, policyText);
        insert.setBytes(3, policyDigest);
        assertEquals(1, insert.executeUpdate());
      }

      MvcResult draftPolicy =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(503, draftPolicy.getResponse().getStatus());

      MvcResult noCsrf =
          mockMvc
              .perform(
                  post(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy/acceptance",
                          ownerWorkId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("policyVersion", policyVersion, "confirmed", true)))
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, noCsrf.getResponse().getStatus());
      assertTrue(noCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement approve =
              connection.prepareStatement(
                  """
                      UPDATE publication_rights_policy_documents
                         SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                       WHERE version = ? AND status = 'DRAFT'
                      """)) {
        approve.setObject(1, approverId);
        approve.setString(2, policyVersion);
        assertEquals(1, approve.executeUpdate());
      }

      MvcResult policyRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, policyRead.getResponse().getStatus());
      assertTrue(
          policyRead.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode policyBody = objectMapper.readTree(policyRead.getResponse().getContentAsString());
      assertEquals(policyVersion, policyBody.path("version").asText());
      assertEquals(policyText, policyBody.path("displayText").asText());
      assertEquals(
          HexFormat.of().formatHex(policyDigest), policyBody.path("policySha256").asText());
      assertTrue(policyBody.path("acceptedAt").isNull());

      MvcResult anotherOwnerPolicyRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          unacceptedWorkId)
                      .cookie(unacceptedCookie))
              .andReturn();
      assertEquals(200, anotherOwnerPolicyRead.getResponse().getStatus());
      assertTrue(
          objectMapper
              .readTree(anotherOwnerPolicyRead.getResponse().getContentAsString())
              .path("acceptedAt")
              .isNull());

      String acceptancePath =
          "/api/v1/authoring/manuscripts/" + ownerWorkId + "/publication/rights-policy/acceptance";
      String acceptedBody =
          objectMapper.writeValueAsString(
              Map.of("policyVersion", policyVersion, "confirmed", true));
      UUID acceptanceKey = UUID.randomUUID();

      MvcResult unchecked =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("policyVersion", policyVersion, "confirmed", false)))
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", acceptanceKey.toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, unchecked.getResponse().getStatus());

      MvcResult noIdempotencyKey =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, noIdempotencyKey.getResponse().getStatus());
      assertTrue(
          noIdempotencyKey.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));

      MvcResult unknownField =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "policyVersion",
                                  policyVersion,
                                  "confirmed",
                                  true,
                                  "rightsScope",
                                  "ALL")))
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, unknownField.getResponse().getStatus());

      MvcResult stalePolicy =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "policyVersion", "stale-publication-policy", "confirmed", true)))
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, stalePolicy.getResponse().getStatus());
      assertTrue(
          stalePolicy
              .getResponse()
              .getContentAsString()
              .contains("PUBLICATION_RIGHTS_POLICY_STALE"));
      assertTrue(stalePolicy.getResponse().getContentAsString().contains(policyVersion));

      MvcResult accepted =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", acceptanceKey.toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, accepted.getResponse().getStatus());
      assertTrue(accepted.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode receipt = objectMapper.readTree(accepted.getResponse().getContentAsString());
      assertEquals(policyVersion, receipt.path("policyVersion").asText());
      assertEquals(HexFormat.of().formatHex(policyDigest), receipt.path("policySha256").asText());
      assertFalse(receipt.path("acceptedAt").asText().isBlank());

      MvcResult exactRetry =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", acceptanceKey.toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, exactRetry.getResponse().getStatus());
      assertEquals(
          receipt.path("id").asText(),
          objectMapper.readTree(exactRetry.getResponse().getContentAsString()).path("id").asText());

      MvcResult changedBodyReplay =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "policyVersion", "different-policy-version", "confirmed", true)))
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", acceptanceKey.toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, changedBodyReplay.getResponse().getStatus());
      assertTrue(
          changedBodyReplay.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      MvcResult duplicateAcceptance =
          mockMvc
              .perform(
                  post(acceptancePath)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, duplicateAcceptance.getResponse().getStatus());
      assertEquals(
          receipt.path("id").asText(),
          objectMapper
              .readTree(duplicateAcceptance.getResponse().getContentAsString())
              .path("id")
              .asText());
      assertEquals(1, publicationRightsPolicyAcceptanceCount(ownerId, ownerWorkId));
      assertEquals(2, publicationRightsPolicyIdempotencyCount(ownerId, ownerWorkId));

      MvcResult foreignAcceptance =
          mockMvc
              .perform(
                  post(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy/acceptance",
                          ownerWorkId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody)
                      .header(
                          "X-CSRF-Token", csrfTokenService.tokenFor(outsiderSession.cookieValue()))
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, foreignAcceptance.getResponse().getStatus());
      assertEquals(1, publicationRightsPolicyAcceptanceCount(ownerId, ownerWorkId));

      MvcResult acceptedPolicyRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, acceptedPolicyRead.getResponse().getStatus());
      assertEquals(
          receipt.path("acceptedAt").asText(),
          objectMapper
              .readTree(acceptedPolicyRead.getResponse().getContentAsString())
              .path("acceptedAt")
              .asText());

      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO publication_rights_policy_documents
                          (version, effective_at, display_text, policy_sha256)
                      VALUES (?, clock_timestamp() - INTERVAL '100 milliseconds', ?, ?)
                      """)) {
        insert.setString(1, malformedDigestVersion);
        insert.setString(2, policyText);
        insert.setBytes(3, sha256("A different digest input."));
        assertEquals(1, insert.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement approve =
              connection.prepareStatement(
                  """
                      UPDATE publication_rights_policy_documents
                         SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                       WHERE version = ? AND status = 'DRAFT'
                      """)) {
        approve.setObject(1, approverId);
        approve.setString(2, malformedDigestVersion);
        assertEquals(1, approve.executeUpdate());
      }
      MvcResult digestMismatch =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy",
                          ownerWorkId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(503, digestMismatch.getResponse().getStatus());
      assertTrue(
          digestMismatch
              .getResponse()
              .getContentAsString()
              .contains("PUBLICATION_RIGHTS_POLICY_STORE_UNAVAILABLE"));
      MvcResult mismatchAcceptance =
          mockMvc
              .perform(
                  post(
                          "/api/v1/authoring/manuscripts/{workId}/publication/rights-policy/acceptance",
                          ownerWorkId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("policyVersion", malformedDigestVersion, "confirmed", true)))
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(503, mismatchAcceptance.getResponse().getStatus());
      assertEquals(1, publicationRightsPolicyAcceptanceCount(ownerId, ownerWorkId));
    } finally {
      if (ownerWorkId != null) {
        deletePublicationRightsPolicyAcceptanceFixture(ownerWorkId);
        deleteBareAuthoredWorkData(ownerId, ownerWorkId);
      }
      if (unacceptedWorkId != null) {
        deleteBareAuthoredWorkData(unacceptedAuthorId, unacceptedWorkId);
      }
      deletePublicationRightsPolicyFixture(malformedDigestVersion);
      deletePublicationRightsPolicyFixture(policyVersion);
      deleteOwnerLibraries(ownerId);
      deleteOwnerLibraries(unacceptedAuthorId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(approverId);
      deleteSessionTestUser(unacceptedAuthorId);
    }
  }

  @Test
  void publicationReadinessIsOwnerScopedContentFreeAndTracksCurrentSnapshots() throws Exception {
    UUID ownerId = createSessionTestUser("Publication readiness owner");
    UUID outsiderId = createSessionTestUser("Publication readiness outsider");
    UUID approverId = createSessionTestUser("Publication readiness policy approver");
    addCurrentPolicyAcceptances(ownerId);
    addCurrentPolicyAcceptances(outsiderId);
    String policyVersion = "publication-readiness-" + UUID.randomUUID().toString().replace("-", "");
    String policyText = "Synthetic publication readiness policy; not legal text.";
    byte[] policyDigest = sha256(policyText);
    UUID workId = null;
    try {
      workId =
          libraryService
              .createPrivateAuthoredWork(
                  ownerId,
                  "Private readiness title that must not be returned",
                  "Synthetic Author",
                  "vi",
                  "publication-readiness-work-" + UUID.randomUUID().toString().replace("-", ""))
              .id();
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String path = "/api/v1/authoring/manuscripts/" + workId + "/publication/readiness";

      assertEquals(401, mockMvc.perform(get(path)).andReturn().getResponse().getStatus());
      MvcResult foreign = mockMvc.perform(get(path).cookie(outsiderCookie)).andReturn();
      assertEquals(404, foreign.getResponse().getStatus());
      assertTrue(foreign.getResponse().getContentAsString().contains("MANUSCRIPT_NOT_FOUND"));

      MvcResult unavailablePolicy = mockMvc.perform(get(path).cookie(ownerCookie)).andReturn();
      assertEquals(200, unavailablePolicy.getResponse().getStatus());
      assertTrue(
          unavailablePolicy
              .getResponse()
              .getHeader(HttpHeaders.CACHE_CONTROL)
              .contains("no-store"));
      assertEquals("no-cache", unavailablePolicy.getResponse().getHeader(HttpHeaders.PRAGMA));
      JsonNode unavailableBody =
          objectMapper.readTree(unavailablePolicy.getResponse().getContentAsString());
      assertFalse(unavailableBody.path("canSubmit").asBoolean());
      assertFalse(unavailableBody.path("publicationRightsPolicyAvailable").asBoolean());
      assertTrue(
          unavailableBody
              .path("blockerCodes")
              .toString()
              .contains("PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED"));
      assertTrue(
          unavailableBody.path("blockerCodes").toString().contains("READY_CHAPTER_REQUIRED"));
      assertEquals(
          unavailableBody.path("snapshotEtag").asText(),
          unavailablePolicy.getResponse().getHeader(HttpHeaders.ETAG));
      String unavailableBodyText = unavailablePolicy.getResponse().getContentAsString();
      assertFalse(unavailableBodyText.contains("Private readiness title"));
      assertFalse(unavailableBodyText.contains("snapshot JSON"));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO publication_rights_policy_documents
                          (version, effective_at, display_text, policy_sha256)
                      VALUES (?, clock_timestamp() - INTERVAL '1 second', ?, ?)
                      """)) {
        insert.setString(1, policyVersion);
        insert.setString(2, policyText);
        insert.setBytes(3, policyDigest);
        assertEquals(1, insert.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement approve =
              connection.prepareStatement(
                  """
                      UPDATE publication_rights_policy_documents
                         SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                       WHERE version = ? AND status = 'DRAFT'
                      """)) {
        approve.setObject(1, approverId);
        approve.setString(2, policyVersion);
        assertEquals(1, approve.executeUpdate());
      }

      MvcResult acceptanceRequired = mockMvc.perform(get(path).cookie(ownerCookie)).andReturn();
      assertEquals(200, acceptanceRequired.getResponse().getStatus());
      JsonNode acceptanceRequiredBody =
          objectMapper.readTree(acceptanceRequired.getResponse().getContentAsString());
      assertTrue(
          acceptanceRequiredBody
              .path("blockerCodes")
              .toString()
              .contains("PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED"));

      insertPublicationRightsPolicyAcceptance(
          UUID.randomUUID(), ownerId, workId, UUID.randomUUID(), policyVersion, policyDigest);
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, workId, 1, "Private volume");
      LibraryRepository.ChapterRecord chapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 1, "Private chapter", "vi");
      libraryService.appendChapterRevision(
          ownerId,
          chapter.id(),
          "plain_text",
          "Private chapter prose that must not be returned.",
          List.of(
              new LibraryService.NewSegment(
                  1,
                  "paragraph",
                  "Private chapter prose that must not be returned.",
                  "readiness-c1-s1")));

      MvcResult ready = mockMvc.perform(get(path).cookie(ownerCookie)).andReturn();
      assertEquals(200, ready.getResponse().getStatus());
      JsonNode readyBody = objectMapper.readTree(ready.getResponse().getContentAsString());
      assertTrue(readyBody.path("canSubmit").asBoolean());
      assertEquals(1, readyBody.path("readyChapterCount").asInt());
      assertTrue(readyBody.path("blockerCodes").isEmpty());
      assertEquals(3, readyBody.path("scopes").size());
      assertEquals(
          readyBody.path("snapshotEtag").asText(), ready.getResponse().getHeader(HttpHeaders.ETAG));
      String readyResponseText = ready.getResponse().getContentAsString();
      assertFalse(readyResponseText.contains("Private readiness title"));
      assertFalse(readyResponseText.contains("Private chapter prose"));
      assertFalse(readyResponseText.contains("Private chapter\""));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement update =
              connection.prepareStatement("UPDATE works SET title = ? WHERE id = ?")) {
        update.setString(1, "Updated private readiness title");
        update.setObject(2, workId);
        assertEquals(1, update.executeUpdate());
      }
      MvcResult updated = mockMvc.perform(get(path).cookie(ownerCookie)).andReturn();
      assertEquals(200, updated.getResponse().getStatus());
      assertNotEquals(
          ready.getResponse().getHeader(HttpHeaders.ETAG),
          updated.getResponse().getHeader(HttpHeaders.ETAG));
      assertFalse(
          updated.getResponse().getContentAsString().contains("Updated private readiness title"));
    } finally {
      if (workId != null) {
        deletePublicationRightsPolicyAcceptanceFixture(workId);
        deleteLibraryUserData(ownerId, workId);
      }
      deletePublicationRightsPolicyFixture(policyVersion);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void glossaryRevisionsAndTermsAreImmutableScopedSnapshots() throws SQLException {
    UUID actorId = UUID.randomUUID();
    UUID glossaryId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID otherGlossaryId = UUID.randomUUID();
    UUID otherRevisionId = UUID.randomUUID();
    UUID termId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement insertUser =
          connection.prepareStatement(
              "INSERT INTO users (id, display_name, status) VALUES (?, 'Glossary Test', 'active')")) {
        insertUser.setObject(1, actorId);
        insertUser.executeUpdate();
      }
      try (PreparedStatement insertGlossary =
          connection.prepareStatement(
              "INSERT INTO glossaries (id, owner_user_id, name, scope_type, source_language,"
                  + " target_language) VALUES (?, ?, 'Test', 'USER_GLOBAL', 'zh-Hans', 'vi')")) {
        insertGlossary.setObject(1, glossaryId);
        insertGlossary.setObject(2, actorId);
        insertGlossary.executeUpdate();
      }
      try (PreparedStatement insertRevision =
          connection.prepareStatement(
              "INSERT INTO glossary_revisions (id, glossary_id, revision_no, created_by_user_id)"
                  + " VALUES (?, ?, 1, ?)")) {
        insertRevision.setObject(1, revisionId);
        insertRevision.setObject(2, glossaryId);
        insertRevision.setObject(3, actorId);
        insertRevision.executeUpdate();
      }
      try (PreparedStatement insertGlossary =
          connection.prepareStatement(
              "INSERT INTO glossaries (id, owner_user_id, name, scope_type, source_language,"
                  + " target_language) VALUES (?, ?, 'Other Test', 'USER_GLOBAL', 'zh-Hans', 'vi')")) {
        insertGlossary.setObject(1, otherGlossaryId);
        insertGlossary.setObject(2, actorId);
        insertGlossary.executeUpdate();
      }
      try (PreparedStatement insertRevision =
          connection.prepareStatement(
              "INSERT INTO glossary_revisions (id, glossary_id, revision_no, created_by_user_id)"
                  + " VALUES (?, ?, 1, ?)")) {
        insertRevision.setObject(1, otherRevisionId);
        insertRevision.setObject(2, otherGlossaryId);
        insertRevision.setObject(3, actorId);
        insertRevision.executeUpdate();
      }
      try (PreparedStatement insertTerm =
          connection.prepareStatement(
              "INSERT INTO glossary_terms (id, revision_id, ordinal, source_term, target_term,"
                  + " case_policy, match_mode) VALUES (?, ?, 1, '金丹', 'Kim Đan', 'INSENSITIVE',"
                  + " 'WHOLE_PHRASE')")) {
        insertTerm.setObject(1, termId);
        insertTerm.setObject(2, revisionId);
        insertTerm.executeUpdate();
      }
      try (PreparedStatement insertAlias =
          connection.prepareStatement(
              "INSERT INTO glossary_aliases (revision_id, term_id, ordinal, alias_text)"
                  + " VALUES (?, ?, 1, 'Golden Core')")) {
        insertAlias.setObject(1, revisionId);
        insertAlias.setObject(2, termId);
        insertAlias.executeUpdate();
      }

      assertThrows(
          SQLException.class,
          () -> {
            try (PreparedStatement updateTerm =
                connection.prepareStatement(
                    "UPDATE glossary_terms SET target_term = 'Đan vàng' WHERE revision_id = ?")) {
              updateTerm.setObject(1, revisionId);
              updateTerm.executeUpdate();
            }
          });
      assertThrows(
          SQLException.class,
          () -> {
            try (PreparedStatement updateScope =
                connection.prepareStatement(
                    "UPDATE glossaries SET scope_type = 'WORK' WHERE id = ?")) {
              updateScope.setObject(1, glossaryId);
              updateScope.executeUpdate();
            }
          });
      assertThrows(
          SQLException.class,
          () -> {
            try (PreparedStatement invalidScope =
                connection.prepareStatement(
                    "INSERT INTO glossaries (id, owner_user_id, name, scope_type,"
                        + " source_language, target_language)"
                        + " VALUES (?, ?, 'Invalid', 'WORK', 'zh-Hans', 'vi')")) {
              invalidScope.setObject(1, UUID.randomUUID());
              invalidScope.setObject(2, actorId);
              invalidScope.executeUpdate();
            }
          });
      assertThrows(
          SQLException.class,
          () -> {
            try (PreparedStatement wrongActiveRevision =
                connection.prepareStatement(
                    "UPDATE glossaries SET active_revision_id = ? WHERE id = ?")) {
              wrongActiveRevision.setObject(1, otherRevisionId);
              wrongActiveRevision.setObject(2, glossaryId);
              wrongActiveRevision.executeUpdate();
            }
          });
      try (PreparedStatement publishRevision =
          connection.prepareStatement(
              "UPDATE glossaries SET active_revision_id = ?, version = 2 WHERE id = ?")) {
        publishRevision.setObject(1, revisionId);
        publishRevision.setObject(2, glossaryId);
        publishRevision.executeUpdate();
      }
      GlossaryRepository.GlossarySnapshot snapshot =
          glossaryRepository.loadActiveRevision(actorId, glossaryId).orElseThrow();
      assertEquals(revisionId, snapshot.activeRevisionId());
      assertEquals(1, snapshot.revisionNumber());
      assertEquals("Golden Core", snapshot.terms().getFirst().aliases().getFirst());
      assertEquals("Kim Đan", snapshot.asDomainRevision().terms().getFirst().targetTerm());
      assertTrue(glossaryRepository.loadActiveRevision(UUID.randomUUID(), glossaryId).isEmpty());

      UUID workspaceReaderId = createSessionTestUser("Glossary Workspace Reader");
      UUID workspaceOutsiderId = createSessionTestUser("Glossary Workspace Outsider");
      var workspace = workspaceService.createWorkspace(actorId, "Glossary Read Workspace");
      UUID workspaceId = workspace.id();
      workspaceService.addMember(
          actorId, workspaceId, workspaceReaderId, WorkspaceRepository.Role.READER);
      UUID workspaceLibraryId =
          libraryService
              .createWorkspaceLibrary(actorId, workspaceId, "Shared Glossary Library")
              .id();
      UUID workspaceGlossaryId = UUID.randomUUID();
      UUID workspaceRevisionId = UUID.randomUUID();
      try {
        try (PreparedStatement insertGlossary =
            connection.prepareStatement(
                "INSERT INTO glossaries (id, name, scope_type, library_id, source_language,"
                    + " target_language) VALUES (?, 'Shared', 'LIBRARY', ?, 'zh-Hans', 'vi')")) {
          insertGlossary.setObject(1, workspaceGlossaryId);
          insertGlossary.setObject(2, workspaceLibraryId);
          insertGlossary.executeUpdate();
        }
        try (PreparedStatement insertRevision =
            connection.prepareStatement(
                "INSERT INTO glossary_revisions (id, glossary_id, revision_no, created_by_user_id)"
                    + " VALUES (?, ?, 1, ?)")) {
          insertRevision.setObject(1, workspaceRevisionId);
          insertRevision.setObject(2, workspaceGlossaryId);
          insertRevision.setObject(3, actorId);
          insertRevision.executeUpdate();
        }
        try (PreparedStatement publishRevision =
            connection.prepareStatement(
                "UPDATE glossaries SET active_revision_id = ? WHERE id = ?")) {
          publishRevision.setObject(1, workspaceRevisionId);
          publishRevision.setObject(2, workspaceGlossaryId);
          publishRevision.executeUpdate();
        }
        assertTrue(
            glossaryRepository
                .loadActiveRevision(workspaceReaderId, workspaceGlossaryId)
                .isPresent());
        try (PreparedStatement suspendReader =
            connection.prepareStatement("UPDATE users SET status = 'suspended' WHERE id = ?")) {
          suspendReader.setObject(1, workspaceReaderId);
          suspendReader.executeUpdate();
        }
        assertTrue(
            glossaryRepository
                .loadActiveRevision(workspaceReaderId, workspaceGlossaryId)
                .isEmpty());
        assertTrue(
            glossaryRepository
                .loadActiveRevision(workspaceOutsiderId, workspaceGlossaryId)
                .isEmpty());
      } finally {
        try (PreparedStatement deleteGlossary =
            connection.prepareStatement("DELETE FROM glossaries WHERE id = ?")) {
          deleteGlossary.setObject(1, workspaceGlossaryId);
          deleteGlossary.executeUpdate();
        }
        try (PreparedStatement deleteLibrary =
            connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
          deleteLibrary.setObject(1, workspaceLibraryId);
          deleteLibrary.executeUpdate();
        }
        try (PreparedStatement deleteWorkspace =
            connection.prepareStatement("DELETE FROM workspaces WHERE id = ?")) {
          deleteWorkspace.setObject(1, workspaceId);
          deleteWorkspace.executeUpdate();
        }
        deleteSessionTestUser(workspaceReaderId);
        deleteSessionTestUser(workspaceOutsiderId);
      }
      try (PreparedStatement deleteUser =
          connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
        deleteUser.setObject(1, actorId);
        deleteUser.executeUpdate();
      }
      try (PreparedStatement remainingSnapshots =
          connection.prepareStatement(
              "SELECT (SELECT COUNT(*) FROM glossaries WHERE id IN (?, ?)) +"
                  + " (SELECT COUNT(*) FROM glossary_revisions WHERE id IN (?, ?))")) {
        remainingSnapshots.setObject(1, glossaryId);
        remainingSnapshots.setObject(2, otherGlossaryId);
        remainingSnapshots.setObject(3, revisionId);
        remainingSnapshots.setObject(4, otherRevisionId);
        try (ResultSet row = remainingSnapshots.executeQuery()) {
          assertTrue(row.next());
          assertEquals(
              0, row.getLong(1), "account erasure must cascade private glossary snapshots");
        }
      }
    }
  }

  @Test
  void glossaryDraftWritesAreAuthorizedVersionedAndDoNotPublish() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Draft Workspace Owner");
    UUID editorId = createSessionTestUser("Glossary Draft Editor");
    UUID readerId = createSessionTestUser("Glossary Draft Reader");
    UUID suspendedEditorId = createSessionTestUser("Glossary Draft Suspended Editor");
    UUID outsiderId = createSessionTestUser("Glossary Draft Outsider");
    UUID libraryGlossaryId = UUID.randomUUID();
    UUID userGlossaryId = UUID.randomUUID();
    UUID workspaceId = null;
    UUID libraryId = null;

    try {
      workspaceId = workspaceService.createWorkspace(ownerId, "Glossary Draft Workspace").id();
      workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      workspaceService.addMember(
          ownerId, workspaceId, suspendedEditorId, WorkspaceRepository.Role.EDITOR);
      libraryId =
          libraryService
              .createWorkspaceLibrary(ownerId, workspaceId, "Draft Glossary Library")
              .id();
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement insertLibraryGlossary =
            connection.prepareStatement(
                """
                    INSERT INTO glossaries
                        (id, name, scope_type, library_id, source_language, target_language)
                    VALUES (?, 'Shared draft', 'LIBRARY', ?, 'zh-Hans', 'vi')
                    """)) {
          insertLibraryGlossary.setObject(1, libraryGlossaryId);
          insertLibraryGlossary.setObject(2, libraryId);
          assertEquals(1, insertLibraryGlossary.executeUpdate());
        }
        try (PreparedStatement insertUserGlossary =
            connection.prepareStatement(
                """
                    INSERT INTO glossaries
                        (id, owner_user_id, name, scope_type, source_language, target_language)
                    VALUES (?, ?, 'Personal draft', 'USER_GLOBAL', 'zh-Hans', 'vi')
                    """)) {
          insertUserGlossary.setObject(1, userGlossaryId);
          insertUserGlossary.setObject(2, ownerId);
          assertEquals(1, insertUserGlossary.executeUpdate());
        }
      }

      UUID stableTermId = UUID.randomUUID();
      var firstTerm =
          new GlossaryRevisionWriteRepository.TermDraft(
              stableTermId,
              "金丹",
              "Kim Đan",
              10,
              GlossaryMatcher.CasePolicy.INSENSITIVE,
              GlossaryMatcher.MatchMode.WHOLE_PHRASE,
              false,
              "cultivation rank",
              List.of("Golden Core", "Core of Gold"));
      var firstDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              editorId, libraryGlossaryId, 1, List.of(firstTerm));
      assertEquals(1, firstDraft.revisionNumber());
      assertNull(firstDraft.parentRevisionId());
      assertEquals(2, firstDraft.glossaryVersion());
      assertTrue(glossaryRepository.loadActiveRevision(editorId, libraryGlossaryId).isEmpty());

      assertThrows(
          GlossaryRevisionWriteRepository.GlossaryNotFoundException.class,
          () ->
              glossaryRevisionWriteRepository.createDraftRevision(
                  readerId, libraryGlossaryId, 2, List.of(firstTerm)));
      assertThrows(
          GlossaryRevisionWriteRepository.GlossaryNotFoundException.class,
          () ->
              glossaryRevisionWriteRepository.createDraftRevision(
                  outsiderId, libraryGlossaryId, 2, List.of(firstTerm)));
      try (Connection connection = dataSource.getConnection();
          PreparedStatement suspend =
              connection.prepareStatement("UPDATE users SET status = 'suspended' WHERE id = ?")) {
        suspend.setObject(1, suspendedEditorId);
        assertEquals(1, suspend.executeUpdate());
      }
      assertThrows(
          GlossaryRevisionWriteRepository.GlossaryNotFoundException.class,
          () ->
              glossaryRevisionWriteRepository.createDraftRevision(
                  suspendedEditorId, libraryGlossaryId, 2, List.of(firstTerm)));

      var secondTerm =
          new GlossaryRevisionWriteRepository.TermDraft(
              stableTermId,
              "金丹",
              "Jindan",
              10,
              GlossaryMatcher.CasePolicy.INSENSITIVE,
              GlossaryMatcher.MatchMode.WHOLE_PHRASE,
              false,
              "updated translation",
              List.of("Golden Core"));
      var secondDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              editorId, libraryGlossaryId, 2, List.of(secondTerm));
      assertEquals(2, secondDraft.revisionNumber());
      assertEquals(firstDraft.revisionId(), secondDraft.parentRevisionId());
      assertEquals(3, secondDraft.glossaryVersion());
      assertThrows(
          GlossaryRevisionWriteRepository.VersionConflictException.class,
          () ->
              glossaryRevisionWriteRepository.createDraftRevision(
                  editorId, libraryGlossaryId, 2, List.of(secondTerm)));

      var personalDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId, userGlossaryId, 1, List.of(firstTerm));
      assertEquals(1, personalDraft.revisionNumber());
      assertTrue(glossaryRepository.loadActiveRevision(ownerId, userGlossaryId).isEmpty());
      assertThrows(
          GlossaryRevisionWriteRepository.GlossaryNotFoundException.class,
          () ->
              glossaryRevisionWriteRepository.createDraftRevision(
                  editorId, userGlossaryId, 2, List.of(firstTerm)));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement selectTerms =
              connection.prepareStatement(
                  """
                      SELECT r.revision_no, t.ordinal AS term_ordinal, t.id AS term_id,
                             t.target_term, a.ordinal AS alias_ordinal, a.alias_text
                      FROM glossary_revisions r
                      JOIN glossary_terms t ON t.revision_id = r.id
                      LEFT JOIN glossary_aliases a ON a.revision_id = t.revision_id
                          AND a.term_id = t.id
                      WHERE r.glossary_id = ?
                      ORDER BY r.revision_no, t.ordinal, a.ordinal
                      """);
          PreparedStatement selectGlossary =
              connection.prepareStatement(
                  """
                      SELECT g.version, g.active_revision_id,
                             (SELECT count(*) FROM glossary_revisions r WHERE r.glossary_id = g.id)
                      FROM glossaries g WHERE g.id = ?
                      """)) {
        selectTerms.setObject(1, libraryGlossaryId);
        try (ResultSet rows = selectTerms.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(1, rows.getInt("revision_no"));
          assertEquals(1, rows.getInt("term_ordinal"));
          assertEquals(stableTermId, rows.getObject("term_id", UUID.class));
          assertEquals("Kim Đan", rows.getString("target_term"));
          assertEquals(1, rows.getInt("alias_ordinal"));
          assertEquals("Golden Core", rows.getString("alias_text"));
          assertTrue(rows.next());
          assertEquals(1, rows.getInt("revision_no"));
          assertEquals(2, rows.getInt("alias_ordinal"));
          assertEquals("Core of Gold", rows.getString("alias_text"));
          assertTrue(rows.next());
          assertEquals(2, rows.getInt("revision_no"));
          assertEquals(1, rows.getInt("term_ordinal"));
          assertEquals(stableTermId, rows.getObject("term_id", UUID.class));
          assertEquals("Jindan", rows.getString("target_term"));
          assertEquals(1, rows.getInt("alias_ordinal"));
          assertEquals("Golden Core", rows.getString("alias_text"));
          assertFalse(rows.next());
        }

        selectGlossary.setObject(1, libraryGlossaryId);
        try (ResultSet row = selectGlossary.executeQuery()) {
          assertTrue(row.next());
          assertEquals(3, row.getLong("version"));
          assertNull(row.getObject("active_revision_id"));
          assertEquals(2, row.getLong("count"), "stale and unauthorized writes must be atomic");
        }
      }
    } finally {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement deleteGlossaries =
            connection.prepareStatement("DELETE FROM glossaries WHERE id IN (?, ?)")) {
          deleteGlossaries.setObject(1, libraryGlossaryId);
          deleteGlossaries.setObject(2, userGlossaryId);
          deleteGlossaries.executeUpdate();
        }
        if (libraryId != null) {
          try (PreparedStatement deleteLibrary =
              connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
            deleteLibrary.setObject(1, libraryId);
            deleteLibrary.executeUpdate();
          }
        }
        if (workspaceId != null) {
          try (PreparedStatement deleteWorkspace =
              connection.prepareStatement("DELETE FROM workspaces WHERE id = ?")) {
            deleteWorkspace.setObject(1, workspaceId);
            deleteWorkspace.executeUpdate();
          }
        }
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(suspendedEditorId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryDraftRouteRequiresSessionCsrfPolicyAndCurrentEtag() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Draft Route Owner");
    UUID blockedId = createSessionTestUser("Glossary Draft Route Policy Blocked");
    UUID outsiderId = createSessionTestUser("Glossary Draft Route Outsider");
    UUID glossaryId = UUID.randomUUID();
    UUID termId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO glossaries
                          (id, owner_user_id, name, scope_type, source_language, target_language)
                      VALUES (?, ?, 'Route draft', 'USER_GLOBAL', 'zh-Hans', 'vi')
                      """)) {
        insert.setObject(1, glossaryId);
        insert.setObject(2, ownerId);
        assertEquals(1, insert.executeUpdate());
      }

      GlossaryRevisionController.DraftRequest validBody =
          new GlossaryRevisionController.DraftRequest(
              List.of(
                  new GlossaryRevisionController.TermRequest(
                      termId,
                      "金丹",
                      "Kim Đan",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "rank name",
                      List.of("Golden Core"))));
      String validJson = objectMapper.writeValueAsString(validBody);
      String route = "/api/v1/glossaries/{glossaryId}/revisions";

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult policyBlocked =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult missingIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(428, missingIfMatch.getResponse().getStatus());
      assertTrue(missingIfMatch.getResponse().getContentAsString().contains("IF_MATCH_REQUIRED"));

      MvcResult malformedIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "v1")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(400, malformedIfMatch.getResponse().getStatus());
      assertTrue(
          malformedIfMatch.getResponse().getContentAsString().contains("INVALID_GLOSSARY_DRAFT"));

      GlossaryRevisionController.DraftRequest invalidBody =
          new GlossaryRevisionController.DraftRequest(
              List.of(
                  new GlossaryRevisionController.TermRequest(
                      UUID.randomUUID(),
                      "金丹",
                      " ",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));
      MvcResult invalidTerm =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(invalidBody)))
              .andReturn();
      assertEquals(400, invalidTerm.getResponse().getStatus());
      assertTrue(invalidTerm.getResponse().getContentAsString().contains("INVALID_GLOSSARY_DRAFT"));

      MvcResult created =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertEquals("\"v2\"", created.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
      assertEquals(glossaryId.toString(), createdBody.path("glossaryId").asText());
      assertEquals(1, createdBody.path("revisionNumber").asInt());
      assertTrue(createdBody.path("parentRevisionId").isNull());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement activate =
              connection.prepareStatement(
                  "UPDATE glossaries SET active_revision_id = ? WHERE id = ?")) {
        activate.setObject(1, UUID.fromString(createdBody.path("revisionId").asText()));
        activate.setObject(2, glossaryId);
        assertEquals(1, activate.executeUpdate());
      }

      MvcResult stale =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(409, stale.getResponse().getStatus());
      assertEquals("\"v2\"", stale.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(stale.getResponse().getContentAsString().contains("GLOSSARY_VERSION_STALE"));

      UUID addedTermId = UUID.randomUUID();
      GlossaryRevisionController.DraftRequest updatedAndAddedBody =
          new GlossaryRevisionController.DraftRequest(
              List.of(
                  new GlossaryRevisionController.TermRequest(
                      termId,
                      "金丹",
                      "Kim Đan cảnh giới",
                      6,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "updated rank name",
                      List.of("Golden Core", "Jindan")),
                  new GlossaryRevisionController.TermRequest(
                      addedTermId,
                      "筑基",
                      "Trúc Cơ",
                      3,
                      GlossaryMatcher.CasePolicy.SENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));
      MvcResult updatedAndAdded =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(updatedAndAddedBody)))
              .andReturn();
      assertEquals(201, updatedAndAdded.getResponse().getStatus());
      assertEquals("\"v3\"", updatedAndAdded.getResponse().getHeader(HttpHeaders.ETAG));
      JsonNode updatedAndAddedResult =
          objectMapper.readTree(updatedAndAdded.getResponse().getContentAsString());
      assertEquals(2, updatedAndAddedResult.path("revisionNumber").asInt());
      assertEquals(
          createdBody.path("revisionId").asText(),
          updatedAndAddedResult.path("parentRevisionId").asText());

      MvcResult latestAfterUpdateAndAdd =
          mockMvc
              .perform(
                  get("/api/v1/glossaries/{glossaryId}/latest-revision", glossaryId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, latestAfterUpdateAndAdd.getResponse().getStatus());
      JsonNode updatedSnapshot =
          objectMapper.readTree(latestAfterUpdateAndAdd.getResponse().getContentAsString());
      assertEquals(2, updatedSnapshot.path("terms").size());
      assertEquals(termId.toString(), updatedSnapshot.path("terms").get(0).path("id").asText());
      assertEquals(
          "Kim Đan cảnh giới", updatedSnapshot.path("terms").get(0).path("targetTerm").asText());
      assertEquals(2, updatedSnapshot.path("terms").get(0).path("aliases").size());
      assertEquals(
          addedTermId.toString(), updatedSnapshot.path("terms").get(1).path("id").asText());

      GlossaryRevisionController.DraftRequest deletedTermBody =
          new GlossaryRevisionController.DraftRequest(
              List.of(
                  new GlossaryRevisionController.TermRequest(
                      addedTermId,
                      "筑基",
                      "Trúc Cơ",
                      3,
                      GlossaryMatcher.CasePolicy.SENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));
      MvcResult deletedTerm =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(deletedTermBody)))
              .andReturn();
      assertEquals(201, deletedTerm.getResponse().getStatus());
      assertEquals("\"v4\"", deletedTerm.getResponse().getHeader(HttpHeaders.ETAG));
      JsonNode deletedTermResult =
          objectMapper.readTree(deletedTerm.getResponse().getContentAsString());
      assertEquals(3, deletedTermResult.path("revisionNumber").asInt());
      assertEquals(
          updatedAndAddedResult.path("revisionId").asText(),
          deletedTermResult.path("parentRevisionId").asText());

      MvcResult latestAfterDelete =
          mockMvc
              .perform(
                  get("/api/v1/glossaries/{glossaryId}/latest-revision", glossaryId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, latestAfterDelete.getResponse().getStatus());
      JsonNode deletedSnapshot =
          objectMapper.readTree(latestAfterDelete.getResponse().getContentAsString());
      assertEquals(1, deletedSnapshot.path("terms").size());
      assertEquals(
          addedTermId.toString(), deletedSnapshot.path("terms").get(0).path("id").asText());
      assertTrue(
          java.util.stream.StreamSupport.stream(deletedSnapshot.path("terms").spliterator(), false)
              .noneMatch(term -> term.path("id").asText().equals(termId.toString())));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  """
                      SELECT revision.revision_no, COUNT(term.id) AS term_count,
                             MAX(CASE WHEN term.id = ? THEN term.target_term END) AS retained_target
                      FROM glossary_revisions revision
                      LEFT JOIN glossary_terms term ON term.revision_id = revision.id
                      WHERE revision.glossary_id = ?
                      GROUP BY revision.revision_no
                      ORDER BY revision.revision_no
                      """)) {
        select.setObject(1, termId);
        select.setObject(2, glossaryId);
        try (ResultSet revisions = select.executeQuery()) {
          assertTrue(revisions.next());
          assertEquals(1, revisions.getInt("revision_no"));
          assertEquals(1, revisions.getInt("term_count"));
          assertEquals("Kim Đan", revisions.getString("retained_target"));
          assertTrue(revisions.next());
          assertEquals(2, revisions.getInt("revision_no"));
          assertEquals(2, revisions.getInt("term_count"));
          assertEquals("Kim Đan cảnh giới", revisions.getString("retained_target"));
          assertTrue(revisions.next());
          assertEquals(3, revisions.getInt("revision_no"));
          assertEquals(1, revisions.getInt("term_count"));
          assertNull(revisions.getString("retained_target"));
          assertFalse(revisions.next());
        }
      }
      GlossaryRepository.GlossarySnapshot activeSnapshot =
          glossaryRepository.loadActiveRevision(ownerId, glossaryId).orElseThrow();
      assertEquals(1, activeSnapshot.revisionNumber());
      assertEquals("Kim Đan", activeSnapshot.terms().get(0).targetTerm());

      MvcResult outsider =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validJson))
              .andReturn();
      assertEquals(404, outsider.getResponse().getStatus());
      assertTrue(outsider.getResponse().getContentAsString().contains("NOT_FOUND"));
    } finally {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement delete =
              connection.prepareStatement("DELETE FROM glossaries WHERE id = ?")) {
        delete.setObject(1, glossaryId);
        delete.executeUpdate();
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(blockedId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryActiveRevisionRouteEnforcesPolicyAndConcealsInaccessibleSnapshots()
      throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Active Read Owner");
    UUID blockedId = createSessionTestUser("Glossary Active Read Policy Blocked");
    UUID outsiderId = createSessionTestUser("Glossary Active Read Outsider");
    UUID glossaryId = UUID.randomUUID();
    UUID emptyGlossaryId = UUID.randomUUID();
    UUID termId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());

      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO glossaries
                          (id, owner_user_id, name, scope_type, source_language, target_language)
                      VALUES (?, ?, ?, 'USER_GLOBAL', 'zh-Hans', 'vi')
                      """)) {
        insert.setObject(1, glossaryId);
        insert.setObject(2, ownerId);
        insert.setString(3, "Active route draft");
        assertEquals(1, insert.executeUpdate());
        insert.setObject(1, emptyGlossaryId);
        insert.setObject(2, ownerId);
        insert.setString(3, "Empty route draft");
        assertEquals(1, insert.executeUpdate());
      }

      GlossaryRevisionWriteRepository.DraftCreated created =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId,
              glossaryId,
              1,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      termId,
                      "金丹",
                      "Kim Đan",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "rank name",
                      List.of("Golden Core"))));
      try (Connection connection = dataSource.getConnection();
          PreparedStatement activate =
              connection.prepareStatement(
                  "UPDATE glossaries SET active_revision_id = ? WHERE id = ?")) {
        activate.setObject(1, created.revisionId());
        activate.setObject(2, glossaryId);
        assertEquals(1, activate.executeUpdate());
      }

      String route = "/api/v1/glossaries/{glossaryId}/active-revision";
      MvcResult unauthenticated = mockMvc.perform(get(route, glossaryId)).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult policyBlocked =
          mockMvc.perform(get(route, glossaryId).cookie(blockedCookie)).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult ownerRead = mockMvc.perform(get(route, glossaryId).cookie(ownerCookie)).andReturn();
      assertEquals(200, ownerRead.getResponse().getStatus());
      assertEquals("\"v2\"", ownerRead.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(ownerRead.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      assertTrue(ownerRead.getResponse().getHeader(HttpHeaders.PRAGMA).contains("no-cache"));
      JsonNode snapshot = objectMapper.readTree(ownerRead.getResponse().getContentAsString());
      assertEquals(glossaryId.toString(), snapshot.path("glossaryId").asText());
      assertEquals(created.revisionId().toString(), snapshot.path("activeRevisionId").asText());
      assertEquals("USER_GLOBAL", snapshot.path("scope").asText());
      assertEquals("金丹", snapshot.path("terms").get(0).path("sourceTerm").asText());
      assertEquals("Golden Core", snapshot.path("terms").get(0).path("aliases").get(0).asText());

      String exportRoute = "/api/v1/glossaries/{glossaryId}/export";
      MvcResult anonymousExport = mockMvc.perform(get(exportRoute, glossaryId)).andReturn();
      assertEquals(401, anonymousExport.getResponse().getStatus());
      MvcResult blockedExport =
          mockMvc.perform(get(exportRoute, glossaryId).cookie(blockedCookie)).andReturn();
      assertEquals(403, blockedExport.getResponse().getStatus());
      MvcResult ownerExport =
          mockMvc
              .perform(get(exportRoute, glossaryId).cookie(ownerCookie).param("format", "json"))
              .andReturn();
      assertEquals(200, ownerExport.getResponse().getStatus());
      assertEquals("application/json;charset=UTF-8", ownerExport.getResponse().getContentType());
      assertEquals("\"v2\"", ownerExport.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          ownerExport.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      assertTrue(
          ownerExport
              .getResponse()
              .getHeader(HttpHeaders.CONTENT_DISPOSITION)
              .contains("-r1.json"));
      JsonNode exported = objectMapper.readTree(ownerExport.getResponse().getContentAsByteArray());
      assertEquals(1, exported.path("version").asInt());
      assertEquals("金丹", exported.path("terms").get(0).path("sourceTerm").asText());
      assertEquals("Golden Core", exported.path("terms").get(0).path("aliases").get(0).asText());

      MvcResult invalidFormat =
          mockMvc
              .perform(get(exportRoute, glossaryId).cookie(ownerCookie).param("format", "xml"))
              .andReturn();
      assertEquals(400, invalidFormat.getResponse().getStatus());
      MvcResult emptyExport =
          mockMvc.perform(get(exportRoute, emptyGlossaryId).cookie(ownerCookie)).andReturn();
      assertEquals(404, emptyExport.getResponse().getStatus());
      MvcResult outsiderExport =
          mockMvc.perform(get(exportRoute, glossaryId).cookie(outsiderCookie)).andReturn();
      assertEquals(404, outsiderExport.getResponse().getStatus());

      MvcResult emptySnapshot =
          mockMvc.perform(get(route, emptyGlossaryId).cookie(ownerCookie)).andReturn();
      assertEquals(404, emptySnapshot.getResponse().getStatus());

      MvcResult outsiderRead =
          mockMvc.perform(get(route, glossaryId).cookie(outsiderCookie)).andReturn();
      assertEquals(404, outsiderRead.getResponse().getStatus());
      assertTrue(outsiderRead.getResponse().getContentAsString().contains("NOT_FOUND"));
    } finally {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement delete =
              connection.prepareStatement("DELETE FROM glossaries WHERE id IN (?, ?)")) {
        delete.setObject(1, glossaryId);
        delete.setObject(2, emptyGlossaryId);
        delete.executeUpdate();
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(blockedId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryLatestRevisionRouteReturnsOnlyTheLatestSnapshotToWriters() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Latest Owner");
    UUID editorId = createSessionTestUser("Glossary Latest Editor");
    UUID readerId = createSessionTestUser("Glossary Latest Reader");
    UUID blockedId = createSessionTestUser("Glossary Latest Policy Blocked");
    UUID outsiderId = createSessionTestUser("Glossary Latest Outsider");
    UUID workspaceId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID glossaryId = UUID.randomUUID();
    UUID emptyGlossaryId = UUID.randomUUID();
    UUID firstTermId = UUID.randomUUID();
    UUID addedTermId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(editorId);
      addCurrentPolicyAcceptances(readerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession editorSession = sessionService.create(editorId).orElseThrow();
      SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var editorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", editorSession.cookieValue());
      var readerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", readerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String editorCsrf = csrfTokenService.tokenFor(editorSession.cookieValue());
      String readerCsrf = csrfTokenService.tokenFor(readerSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());

      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          try (PreparedStatement insertWorkspace =
              connection.prepareStatement(
                  "INSERT INTO workspaces (id, name, created_by_user_id) VALUES (?, ?, ?)")) {
            insertWorkspace.setObject(1, workspaceId);
            insertWorkspace.setString(2, "Glossary latest revision fixture");
            insertWorkspace.setObject(3, ownerId);
            assertEquals(1, insertWorkspace.executeUpdate());
          }
          try (PreparedStatement insertMember =
              connection.prepareStatement(
                  "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)")) {
            for (var member :
                List.of(
                    new Object[] {ownerId, "owner"},
                    new Object[] {editorId, "editor"},
                    new Object[] {readerId, "reader"})) {
              insertMember.setObject(1, workspaceId);
              insertMember.setObject(2, member[0]);
              insertMember.setString(3, (String) member[1]);
              insertMember.addBatch();
            }
            assertEquals(3, insertMember.executeBatch().length);
          }
          try (PreparedStatement insertLibrary =
              connection.prepareStatement(
                  "INSERT INTO libraries (id, owner_user_id, workspace_id, name, is_default)"
                      + " VALUES (?, ?, ?, 'Glossary latest fixture', FALSE)")) {
            insertLibrary.setObject(1, libraryId);
            insertLibrary.setObject(2, ownerId);
            insertLibrary.setObject(3, workspaceId);
            assertEquals(1, insertLibrary.executeUpdate());
          }
          try (PreparedStatement insertGlossary =
              connection.prepareStatement(
                  "INSERT INTO glossaries"
                      + " (id, library_id, name, scope_type, source_language, target_language)"
                      + " VALUES (?, ?, ?, 'LIBRARY', 'zh-Hans', 'vi')")) {
            insertGlossary.setObject(1, glossaryId);
            insertGlossary.setObject(2, libraryId);
            insertGlossary.setString(3, "Library glossary with drafts");
            assertEquals(1, insertGlossary.executeUpdate());
            insertGlossary.setObject(1, emptyGlossaryId);
            insertGlossary.setString(3, "Empty library glossary");
            assertEquals(1, insertGlossary.executeUpdate());
          }
          connection.commit();
        } catch (SQLException | RuntimeException exception) {
          connection.rollback();
          throw exception;
        }
      }

      GlossaryRevisionWriteRepository.DraftCreated firstDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId,
              glossaryId,
              1,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      firstTermId,
                      "金丹",
                      "Kim Đan",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "published value",
                      List.of("Golden Core"))));
      try (Connection connection = dataSource.getConnection();
          PreparedStatement activate =
              connection.prepareStatement(
                  "UPDATE glossaries SET active_revision_id = ? WHERE id = ?")) {
        activate.setObject(1, firstDraft.revisionId());
        activate.setObject(2, glossaryId);
        assertEquals(1, activate.executeUpdate());
      }
      GlossaryRevisionWriteRepository.DraftCreated latestDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              editorId,
              glossaryId,
              2,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      firstTermId,
                      "金丹",
                      "Kim Đan (draft)",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "latest draft value",
                      List.of("Golden Core", "Golden Elixir")),
                  new GlossaryRevisionWriteRepository.TermDraft(
                      addedTermId,
                      "元婴",
                      "Nguyên Anh",
                      6,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));

      String route = "/api/v1/glossaries/{glossaryId}/latest-revision";
      assertEquals(
          401, mockMvc.perform(get(route, glossaryId)).andReturn().getResponse().getStatus());

      MvcResult policyBlocked =
          mockMvc.perform(get(route, glossaryId).cookie(blockedCookie)).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      String previewRoute = "/api/v1/glossaries/{glossaryId}/revisions/{revisionId}/preview";
      for (var writer :
          List.of(new Object[] {ownerCookie, ownerCsrf}, new Object[] {editorCookie, editorCsrf})) {
        var writerCookie = (jakarta.servlet.http.Cookie) writer[0];
        String writerCsrf = (String) writer[1];
        MvcResult writerRead =
            mockMvc.perform(get(route, glossaryId).cookie(writerCookie)).andReturn();
        assertEquals(200, writerRead.getResponse().getStatus());
        assertEquals("\"v3\"", writerRead.getResponse().getHeader(HttpHeaders.ETAG));
        assertTrue(
            writerRead.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
        assertTrue(writerRead.getResponse().getHeader(HttpHeaders.PRAGMA).contains("no-cache"));
        JsonNode body = objectMapper.readTree(writerRead.getResponse().getContentAsString());
        assertEquals(glossaryId.toString(), body.path("glossaryId").asText());
        assertEquals(firstDraft.revisionId().toString(), body.path("activeRevisionId").asText());
        assertEquals(latestDraft.revisionId().toString(), body.path("revisionId").asText());
        assertEquals(2, body.path("revisionNumber").asInt());
        assertEquals("Kim Đan (draft)", body.path("terms").get(0).path("targetTerm").asText());
        assertEquals("Golden Elixir", body.path("terms").get(0).path("aliases").get(1).asText());
        assertEquals(addedTermId.toString(), body.path("terms").get(1).path("id").asText());

        MvcResult writerPreview =
            mockMvc
                .perform(
                    post(previewRoute, glossaryId, latestDraft.revisionId())
                        .cookie(writerCookie)
                        .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, writerCsrf)
                        .header(HttpHeaders.IF_MATCH, "\"v3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                Map.of("sampleSource", "Golden Elixir appears."))))
                .andReturn();
        assertEquals(200, writerPreview.getResponse().getStatus());
        JsonNode writerPreviewBody =
            objectMapper.readTree(writerPreview.getResponse().getContentAsString());
        assertEquals(2, writerPreviewBody.path("changeCount").asInt());
        assertEquals(1, writerPreviewBody.path("matchCount").asInt());
        assertEquals(
            latestDraft.revisionId().toString(),
            writerPreviewBody.path("matches").get(0).path("revisionId").asText());
      }

      MvcResult readerRead =
          mockMvc.perform(get(route, glossaryId).cookie(readerCookie)).andReturn();
      assertEquals(404, readerRead.getResponse().getStatus());
      MvcResult outsiderRead =
          mockMvc.perform(get(route, glossaryId).cookie(outsiderCookie)).andReturn();
      assertEquals(404, outsiderRead.getResponse().getStatus());
      MvcResult readerPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, latestDraft.revisionId())
                      .cookie(readerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, readerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(404, readerPreview.getResponse().getStatus());
      MvcResult outsiderPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, latestDraft.revisionId())
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(404, outsiderPreview.getResponse().getStatus());
      MvcResult emptyRead =
          mockMvc.perform(get(route, emptyGlossaryId).cookie(ownerCookie)).andReturn();
      assertEquals(404, emptyRead.getResponse().getStatus());

      MvcResult activeRead =
          mockMvc
              .perform(
                  get("/api/v1/glossaries/{glossaryId}/active-revision", glossaryId)
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, activeRead.getResponse().getStatus());
      JsonNode activeBody = objectMapper.readTree(activeRead.getResponse().getContentAsString());
      assertEquals(
          firstDraft.revisionId().toString(), activeBody.path("activeRevisionId").asText());
      assertEquals("Kim Đan", activeBody.path("terms").get(0).path("targetTerm").asText());

      String exportRoute = "/api/v1/glossaries/{glossaryId}/export";
      MvcResult readerActiveExport =
          mockMvc
              .perform(
                  get(exportRoute, glossaryId)
                      .cookie(readerCookie)
                      .param("format", "json")
                      .param("revision", "active"))
              .andReturn();
      assertEquals(200, readerActiveExport.getResponse().getStatus());
      JsonNode readerActiveDocument =
          objectMapper.readTree(readerActiveExport.getResponse().getContentAsByteArray());
      assertEquals(
          "Kim Đan", readerActiveDocument.path("terms").get(0).path("targetTerm").asText());

      MvcResult readerLatestExport =
          mockMvc
              .perform(
                  get(exportRoute, glossaryId)
                      .cookie(readerCookie)
                      .param("format", "json")
                      .param("revision", "latest"))
              .andReturn();
      assertEquals(404, readerLatestExport.getResponse().getStatus());

      MvcResult writerLatestExport =
          mockMvc
              .perform(
                  get(exportRoute, glossaryId)
                      .cookie(editorCookie)
                      .param("format", "json")
                      .param("revision", "latest"))
              .andReturn();
      assertEquals(200, writerLatestExport.getResponse().getStatus());
      assertEquals("\"v3\"", writerLatestExport.getResponse().getHeader(HttpHeaders.ETAG));
      JsonNode writerLatestDocument =
          objectMapper.readTree(writerLatestExport.getResponse().getContentAsByteArray());
      assertEquals(
          "Kim Đan (draft)", writerLatestDocument.path("terms").get(0).path("targetTerm").asText());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement verifyExportDidNotWrite =
              connection.prepareStatement(
                  """
                      SELECT g.version, g.active_revision_id,
                        (SELECT count(*) FROM glossary_revisions r WHERE r.glossary_id = g.id)
                      FROM glossaries g WHERE g.id = ?
                      """)) {
        verifyExportDidNotWrite.setObject(1, glossaryId);
        try (ResultSet result = verifyExportDidNotWrite.executeQuery()) {
          assertTrue(result.next());
          assertEquals(3, result.getLong(1));
          assertEquals(firstDraft.revisionId(), result.getObject(2, UUID.class));
          assertEquals(2, result.getInt(3));
        }
      }
    } finally {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement deleteGlossaries =
            connection.prepareStatement("DELETE FROM glossaries WHERE id IN (?, ?)")) {
          deleteGlossaries.setObject(1, glossaryId);
          deleteGlossaries.setObject(2, emptyGlossaryId);
          deleteGlossaries.executeUpdate();
        }
        try (PreparedStatement deleteLibrary =
            connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
          deleteLibrary.setObject(1, libraryId);
          deleteLibrary.executeUpdate();
        }
        try (PreparedStatement deleteWorkspace =
            connection.prepareStatement("DELETE FROM workspaces WHERE id = ?")) {
          deleteWorkspace.setObject(1, workspaceId);
          deleteWorkspace.executeUpdate();
        }
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(blockedId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryPublicationRouteLintsLocksVersionsAndWritesImmutableAudit() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Publish Owner");
    UUID blockedId = createSessionTestUser("Glossary Publish Policy Blocked");
    UUID outsiderId = createSessionTestUser("Glossary Publish Outsider");
    UUID glossaryId = UUID.randomUUID();
    UUID firstTermId = UUID.randomUUID();
    UUID conflictTermId = UUID.randomUUID();
    UUID conflictTermIdTwo = UUID.randomUUID();
    UUID latestTermId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO glossaries
                          (id, owner_user_id, name, scope_type, source_language, target_language)
                      VALUES (?, ?, 'Publish route glossary', 'USER_GLOBAL', 'zh-Hans', 'vi')
                      """)) {
        insert.setObject(1, glossaryId);
        insert.setObject(2, ownerId);
        assertEquals(1, insert.executeUpdate());
      }

      GlossaryRevisionWriteRepository.DraftCreated firstDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId,
              glossaryId,
              1,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      firstTermId,
                      "金丹",
                      "Kim Đan",
                      5,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      "rank name",
                      List.of("Golden Core"))));
      String route = "/api/v1/glossaries/{glossaryId}/revisions/{revisionId}/publish";
      String previewRoute = "/api/v1/glossaries/{glossaryId}/revisions/{revisionId}/preview";

      MvcResult previewPolicyBlocked =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(403, previewPolicyBlocked.getResponse().getStatus());
      assertTrue(
          previewPolicyBlocked
              .getResponse()
              .getContentAsString()
              .contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult previewMissingCsrf =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(403, previewMissingCsrf.getResponse().getStatus());
      assertTrue(previewMissingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      String anonymousSessionToken =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
      MvcResult anonymousPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", anonymousSessionToken))
                      .header(
                          SessionCsrfTokenService.TOKEN_HEADER_NAME,
                          csrfTokenService.tokenFor(anonymousSessionToken))
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(401, anonymousPreview.getResponse().getStatus());
      assertTrue(anonymousPreview.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

      MvcResult previewMissingIfMatch =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(428, previewMissingIfMatch.getResponse().getStatus());

      MvcResult previewMalformedIfMatch =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "v2")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(400, previewMalformedIfMatch.getResponse().getStatus());

      MvcResult oversizedSample =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("sampleSource", "x".repeat(4001)))))
              .andReturn();
      assertEquals(413, oversizedSample.getResponse().getStatus());

      MvcResult firstPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("sampleSource", "金丹 and Golden Core"))))
              .andReturn();
      assertEquals(200, firstPreview.getResponse().getStatus());
      assertEquals("\"v2\"", firstPreview.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          firstPreview.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode firstPreviewBody =
          objectMapper.readTree(firstPreview.getResponse().getContentAsString());
      assertEquals(1, firstPreviewBody.path("changeCount").asInt());
      JsonNode firstPreviewChange = firstPreviewBody.path("changes").get(0);
      assertTrue(firstPreviewChange.path("before").isNull());
      assertEquals(1, firstPreviewChange.path("after").path("ordinal").asInt());
      assertEquals("rank name", firstPreviewChange.path("after").path("contextNote").asText());
      assertEquals(1, firstPreviewBody.path("warningCount").asInt());
      assertEquals(2, firstPreviewBody.path("matchCount").asInt());
      assertTrue(firstPreviewBody.path("canPublish").asBoolean());

      MvcResult unauthenticated =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(403, unauthenticated.getResponse().getStatus());
      assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult policyBlocked =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf))
              .andReturn();
      assertEquals(428, missingIfMatch.getResponse().getStatus());

      MvcResult malformedIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "v2"))
              .andReturn();
      assertEquals(400, malformedIfMatch.getResponse().getStatus());

      MvcResult firstPublished =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(200, firstPublished.getResponse().getStatus());
      assertEquals("\"v3\"", firstPublished.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          firstPublished.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode firstPublishedBody =
          objectMapper.readTree(firstPublished.getResponse().getContentAsString());
      assertEquals(
          firstDraft.revisionId().toString(), firstPublishedBody.path("revisionId").asText());
      assertEquals(1, firstPublishedBody.path("revisionNumber").asInt());
      assertFalse(firstPublishedBody.path("alreadyActive").asBoolean());

      MvcResult retryFirstPublish =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(200, retryFirstPublish.getResponse().getStatus());
      assertEquals("\"v3\"", retryFirstPublish.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          objectMapper
              .readTree(retryFirstPublish.getResponse().getContentAsString())
              .path("alreadyActive")
              .asBoolean());

      GlossaryRevisionWriteRepository.DraftCreated conflictingDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId,
              glossaryId,
              3,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      conflictTermId,
                      "玉佩",
                      "Ngọc bội",
                      1,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of()),
                  new GlossaryRevisionWriteRepository.TermDraft(
                      conflictTermIdTwo,
                      "玉佩",
                      "Ngọc phù",
                      1,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));
      MvcResult conflictPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, conflictingDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(Map.of("sampleSource", "玉佩"))))
              .andReturn();
      assertEquals(200, conflictPreview.getResponse().getStatus());
      JsonNode conflictPreviewBody =
          objectMapper.readTree(conflictPreview.getResponse().getContentAsString());
      assertFalse(conflictPreviewBody.path("canPublish").asBoolean());
      assertEquals(1, conflictPreviewBody.path("errorCount").asInt());
      assertEquals(1, conflictPreviewBody.path("conflictCount").asInt());

      MvcResult blockedByLint =
          mockMvc
              .perform(
                  post(route, glossaryId, conflictingDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\""))
              .andReturn();
      assertEquals(422, blockedByLint.getResponse().getStatus());
      assertTrue(
          blockedByLint
              .getResponse()
              .getContentAsString()
              .contains("GLOSSARY_REVISION_NOT_PUBLISHABLE"));
      assertTrue(
          blockedByLint
              .getResponse()
              .getContentAsString()
              .contains("EQUAL_PRECEDENCE_DIFFERENT_OUTCOMES"));
      assertEquals(
          firstDraft.revisionId(),
          glossaryRepository
              .loadActiveRevision(ownerId, glossaryId)
              .orElseThrow()
              .activeRevisionId());

      GlossaryRevisionWriteRepository.DraftCreated latestDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId,
              glossaryId,
              4,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      latestTermId,
                      "筑基",
                      "Trúc Cơ",
                      3,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));

      MvcResult stalePreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, latestDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(409, stalePreview.getResponse().getStatus());
      assertEquals("\"v5\"", stalePreview.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          stalePreview.getResponse().getContentAsString().contains("GLOSSARY_VERSION_STALE"));

      MvcResult previewOutsider =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, latestDraft.revisionId())
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v5\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(404, previewOutsider.getResponse().getStatus());

      MvcResult replayOlderActiveRevision =
          mockMvc
              .perform(
                  post(route, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\""))
              .andReturn();
      assertEquals(200, replayOlderActiveRevision.getResponse().getStatus());
      assertEquals("\"v5\"", replayOlderActiveRevision.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          objectMapper
              .readTree(replayOlderActiveRevision.getResponse().getContentAsString())
              .path("alreadyActive")
              .asBoolean());

      MvcResult previewAlreadyActive =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, firstDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(409, previewAlreadyActive.getResponse().getStatus());
      assertTrue(
          previewAlreadyActive
              .getResponse()
              .getContentAsString()
              .contains("GLOSSARY_REVISION_ALREADY_ACTIVE"));

      MvcResult nonLatest =
          mockMvc
              .perform(
                  post(route, glossaryId, conflictingDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v5\""))
              .andReturn();
      assertEquals(409, nonLatest.getResponse().getStatus());
      assertTrue(
          nonLatest.getResponse().getContentAsString().contains("GLOSSARY_REVISION_NOT_LATEST"));
      assertEquals("\"v5\"", nonLatest.getResponse().getHeader(HttpHeaders.ETAG));

      MvcResult previewNonLatest =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, conflictingDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v5\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(409, previewNonLatest.getResponse().getStatus());
      assertTrue(
          previewNonLatest
              .getResponse()
              .getContentAsString()
              .contains("GLOSSARY_REVISION_NOT_LATEST"));

      MvcResult staleVersion =
          mockMvc
              .perform(
                  post(route, glossaryId, latestDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\""))
              .andReturn();
      assertEquals(409, staleVersion.getResponse().getStatus());
      assertEquals("\"v5\"", staleVersion.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          staleVersion.getResponse().getContentAsString().contains("GLOSSARY_VERSION_STALE"));

      MvcResult outsider =
          mockMvc
              .perform(
                  post(route, glossaryId, latestDraft.revisionId())
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v5\""))
              .andReturn();
      assertEquals(404, outsider.getResponse().getStatus());

      MvcResult latestPublished =
          mockMvc
              .perform(
                  post(route, glossaryId, latestDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v5\""))
              .andReturn();
      assertEquals(200, latestPublished.getResponse().getStatus());
      assertEquals("\"v6\"", latestPublished.getResponse().getHeader(HttpHeaders.ETAG));
      assertEquals(
          latestDraft.revisionId(),
          glossaryRepository
              .loadActiveRevision(ownerId, glossaryId)
              .orElseThrow()
              .activeRevisionId());

      long publicationEventCount;
      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  "SELECT count(*) FROM glossary_publication_events WHERE glossary_id = ?")) {
        select.setObject(1, glossaryId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          publicationEventCount = row.getLong(1);
        }
      }
      assertEquals(2, publicationEventCount);

      assertThrows(
          SQLException.class,
          () -> {
            try (Connection connection = dataSource.getConnection();
                PreparedStatement update =
                    connection.prepareStatement(
                        "UPDATE glossary_publication_events SET glossary_version = 99"
                            + " WHERE glossary_id = ?")) {
              update.setObject(1, glossaryId);
              update.executeUpdate();
            }
          });

      List<GlossaryRevisionWriteRepository.TermDraft> boundedPreviewTerms = new ArrayList<>();
      List<String> boundedPreviewPatterns = new ArrayList<>();
      for (int pair = 0; pair < 51; pair++) {
        String pattern = "collision phrase " + pair;
        boundedPreviewPatterns.add(pattern);
        for (String targetSuffix : List.of("one", "two")) {
          boundedPreviewTerms.add(
              new GlossaryRevisionWriteRepository.TermDraft(
                  UUID.randomUUID(),
                  pattern,
                  "target " + targetSuffix + " " + pair,
                  7,
                  GlossaryMatcher.CasePolicy.INSENSITIVE,
                  GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                  false,
                  null,
                  List.of()));
        }
      }
      GlossaryRevisionWriteRepository.DraftCreated largePreviewDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              ownerId, glossaryId, 6, boundedPreviewTerms);
      MvcResult boundedPreview =
          mockMvc
              .perform(
                  post(previewRoute, glossaryId, largePreviewDraft.revisionId())
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v7\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("sampleSource", String.join(" ", boundedPreviewPatterns)))))
              .andReturn();
      assertEquals(200, boundedPreview.getResponse().getStatus());
      JsonNode boundedPreviewBody =
          objectMapper.readTree(boundedPreview.getResponse().getContentAsString());
      assertEquals(103, boundedPreviewBody.path("changeCount").asInt());
      assertEquals(100, boundedPreviewBody.path("changes").size());
      assertTrue(boundedPreviewBody.path("changesTruncated").asBoolean());
      assertEquals(51, boundedPreviewBody.path("errorCount").asInt());
      assertEquals(50, boundedPreviewBody.path("lintIssues").size());
      assertTrue(boundedPreviewBody.path("lintIssuesTruncated").asBoolean());
      assertEquals(51, boundedPreviewBody.path("conflictCount").asInt());
      assertEquals(50, boundedPreviewBody.path("conflicts").size());
      assertTrue(boundedPreviewBody.path("conflictsTruncated").asBoolean());
      assertEquals(0, boundedPreviewBody.path("matchCount").asInt());
    } finally {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement delete =
              connection.prepareStatement("DELETE FROM glossaries WHERE id = ?")) {
        delete.setObject(1, glossaryId);
        delete.executeUpdate();
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(blockedId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryImportPreviewRequiresCurrentWriterAndNeverChangesTheGlossary() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Import Preview Owner");
    UUID editorId = createSessionTestUser("Glossary Import Preview Editor");
    UUID readerId = createSessionTestUser("Glossary Import Preview Reader");
    UUID blockedId = createSessionTestUser("Glossary Import Preview Policy Blocked");
    UUID outsiderId = createSessionTestUser("Glossary Import Preview Outsider");
    UUID glossaryId = UUID.randomUUID();
    UUID emptyGlossaryId = UUID.randomUUID();
    UUID firstTermId = UUID.randomUUID();
    UUID secondTermId = UUID.randomUUID();
    UUID workspaceId = null;
    UUID libraryId = null;
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(editorId);
      addCurrentPolicyAcceptances(readerId);
      addCurrentPolicyAcceptances(outsiderId);
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Glossary Import Preview Workspace");
      workspaceId = workspace.id();
      workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      libraryId =
          libraryService
              .createWorkspaceLibrary(ownerId, workspaceId, "Glossary Import Preview Library")
              .id();
      for (UUID id : List.of(glossaryId, emptyGlossaryId)) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement insert =
                connection.prepareStatement(
                    """
                        INSERT INTO glossaries
                            (id, name, scope_type, library_id, source_language, target_language)
                        VALUES (?, 'Import preview glossary', 'LIBRARY', ?, 'zh-Hans', 'vi')
                        """)) {
          insert.setObject(1, id);
          insert.setObject(2, libraryId);
          assertEquals(1, insert.executeUpdate());
        }
      }
      GlossaryRevisionWriteRepository.DraftCreated draft =
          glossaryRevisionWriteRepository.createDraftRevision(
              editorId,
              glossaryId,
              1,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      firstTermId,
                      "金丹",
                      "Kim Đan",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Golden Core")),
                  new GlossaryRevisionWriteRepository.TermDraft(
                      secondTermId,
                      "玉佩",
                      "Ngọc bội",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Jade Pendant"))));
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession editorSession = sessionService.create(editorId).orElseThrow();
      SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var editorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", editorSession.cookieValue());
      var readerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", readerSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String editorCsrf = csrfTokenService.tokenFor(editorSession.cookieValue());
      String readerCsrf = csrfTokenService.tokenFor(readerSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());

      MvcResult published =
          mockMvc
              .perform(
                  post(
                          "/api/v1/glossaries/{glossaryId}/revisions/{revisionId}/publish",
                          glossaryId,
                          draft.revisionId())
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v2\""))
              .andReturn();
      assertEquals(200, published.getResponse().getStatus());
      assertEquals("\"v3\"", published.getResponse().getHeader(HttpHeaders.ETAG));

      UUID draftOnlyTermId = UUID.randomUUID();
      GlossaryRevisionWriteRepository.DraftCreated latestDraft =
          glossaryRevisionWriteRepository.createDraftRevision(
              editorId,
              glossaryId,
              3,
              List.of(
                  new GlossaryRevisionWriteRepository.TermDraft(
                      firstTermId,
                      "金丹",
                      "Kim Đan",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Golden Core")),
                  new GlossaryRevisionWriteRepository.TermDraft(
                      secondTermId,
                      "玉佩",
                      "Ngọc bội draft",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Jade Pendant")),
                  new GlossaryRevisionWriteRepository.TermDraft(
                      draftOnlyTermId,
                      "兔子",
                      "thỏ",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Rabbit"))));
      assertEquals(2, latestDraft.revisionNumber());
      assertEquals(draft.revisionId(), latestDraft.parentRevisionId());
      assertEquals(4, latestDraft.glossaryVersion());

      String route = "/api/v1/glossaries/{glossaryId}/terms/import-preview";
      String importJson =
          "{\"version\":1,\"terms\":["
              + "{\"sourceTerm\":\"金丹\",\"targetTerm\":\"Kim Đan\",\"aliases\":[\"Golden Core\"]},"
              + "{\"sourceTerm\":\"玉佩\",\"targetTerm\":\"Ngọc phù\"},"
              + "{\"sourceTerm\":\"银剑\",\"targetTerm\":\"Kiếm bạc\",\"aliases\":[\"Jade Pendant\"]},"
              + "{\"sourceTerm\":\"丹田\",\"targetTerm\":\"Đan Điền\"}]}";

      MvcResult unauthenticated =
          mockMvc
              .perform(post(route, glossaryId).header(HttpHeaders.IF_MATCH, "\"v4\""))
              .andReturn();
      assertEquals(403, unauthenticated.getResponse().getStatus());
      assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult policyBlocked =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult missingIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(428, missingIfMatch.getResponse().getStatus());

      MvcResult staleIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v3\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(409, staleIfMatch.getResponse().getStatus());
      assertEquals("\"v4\"", staleIfMatch.getResponse().getHeader(HttpHeaders.ETAG));

      MvcResult malformedIfMatch =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "v4")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(400, malformedIfMatch.getResponse().getStatus());

      MvcResult unsupportedType =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.TEXT_PLAIN)
                      .content("term,target"))
              .andReturn();
      assertEquals(415, unsupportedType.getResponse().getStatus());
      assertTrue(
          unsupportedType.getResponse().getContentAsString().contains("IMPORT_UNSUPPORTED_FORMAT"));

      MvcResult oversizedFile =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(new byte[GlossaryImportParser.MAX_IMPORT_BYTES + 1]))
              .andReturn();
      assertEquals(413, oversizedFile.getResponse().getStatus());
      assertTrue(
          oversizedFile.getResponse().getContentAsString().contains("IMPORT_LIMIT_EXCEEDED"));

      MvcResult malformedFile =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"version\":1,\"terms\":[{}]}"))
              .andReturn();
      assertEquals(422, malformedFile.getResponse().getStatus());
      assertTrue(
          malformedFile.getResponse().getContentAsString().contains("GLOSSARY_IMPORT_INVALID"));

      MvcResult readerDenied =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(readerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, readerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(404, readerDenied.getResponse().getStatus());

      MvcResult outsiderDenied =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(404, outsiderDenied.getResponse().getStatus());

      MvcResult ownerPreview =
          mockMvc
              .perform(
                  post(route, glossaryId)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(importJson))
              .andReturn();
      assertEquals(200, ownerPreview.getResponse().getStatus());
      assertEquals("\"v4\"", ownerPreview.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          ownerPreview.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode preview = objectMapper.readTree(ownerPreview.getResponse().getContentAsString());
      assertEquals("LIBRARY", preview.path("scope").asText());
      assertEquals("金丹", preview.path("currentTerms").get(0).path("sourceTerm").asText());
      assertEquals(3, preview.path("currentTerms").size());
      assertEquals(draft.revisionId().toString(), preview.path("activeRevisionId").asText());
      assertEquals(latestDraft.revisionId().toString(), preview.path("baseRevisionId").asText());
      assertEquals(2, preview.path("baseRevisionNumber").asInt());
      assertEquals("兔子", preview.path("currentTerms").get(2).path("sourceTerm").asText());
      assertEquals(4, preview.path("counts").path("imported").asInt());
      assertEquals(1, preview.path("counts").path("add").asInt());
      assertEquals(1, preview.path("counts").path("update").asInt());
      assertEquals(1, preview.path("counts").path("unchanged").asInt());
      assertEquals(1, preview.path("counts").path("requiresResolution").asInt());
      assertEquals(
          secondTermId.toString(),
          preview
              .path("importedTerms")
              .get(2)
              .path("existingMatches")
              .get(0)
              .path("termId")
              .asText());
      assertEquals("UNAVAILABLE", preview.path("translationImpact").path("status").asText());

      MvcResult emptyGlossaryPreview =
          mockMvc
              .perform(
                  post(route, emptyGlossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"version\":1,\"terms\":[{\"sourceTerm\":\"new\",\"targetTerm\":\"mới\"}]}"))
              .andReturn();
      assertEquals(200, emptyGlossaryPreview.getResponse().getStatus());
      JsonNode emptyPreview =
          objectMapper.readTree(emptyGlossaryPreview.getResponse().getContentAsString());
      assertTrue(emptyPreview.path("activeRevisionId").isNull());
      assertEquals(
          "ADD", emptyPreview.path("importedTerms").get(0).path("suggestedAction").asText());

      String applyRoute = "/api/v1/glossaries/{glossaryId}/terms/import-apply";
      MvcResult missingApplyCsrf =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "00000000-0000-4000-8000-000000000001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertEquals(403, missingApplyCsrf.getResponse().getStatus());

      String malformedApplyBody =
          objectMapper.writeValueAsString(
              new GlossaryImportApplyController.ImportApplyRequest(
                  "JSON",
                  Base64.getEncoder().encodeToString(importJson.getBytes(StandardCharsets.UTF_8)),
                  List.of(
                      new GlossaryImportApplyService.RowResolution(
                          1, GlossaryImportApplyService.ResolutionAction.SKIP, null, null))));
      MvcResult incompleteResolutions =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "glossary-apply-shared-key-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(malformedApplyBody))
              .andReturn();
      assertEquals(422, incompleteResolutions.getResponse().getStatus());
      assertTrue(
          incompleteResolutions
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_RESOLUTIONS_INCOMPLETE"));

      List<GlossaryImportApplyService.RowResolution> unresolvedRows =
          List.of(
              new GlossaryImportApplyService.RowResolution(
                  1, GlossaryImportApplyService.ResolutionAction.SKIP, null, null),
              new GlossaryImportApplyService.RowResolution(
                  2, GlossaryImportApplyService.ResolutionAction.SKIP, null, null),
              new GlossaryImportApplyService.RowResolution(
                  3,
                  GlossaryImportApplyService.ResolutionAction.ADD,
                  null,
                  new GlossaryImportApplyService.ResolvedTerm(
                      "银剑",
                      "Kiếm bạc",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Jade Pendant"))),
              new GlossaryImportApplyService.RowResolution(
                  4, GlossaryImportApplyService.ResolutionAction.SKIP, null, null));
      String unresolvedApplyBody =
          objectMapper.writeValueAsString(
              new GlossaryImportApplyController.ImportApplyRequest(
                  "JSON",
                  Base64.getEncoder().encodeToString(importJson.getBytes(StandardCharsets.UTF_8)),
                  unresolvedRows));
      MvcResult unresolvedConflict =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "glossary-apply-shared-key-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(unresolvedApplyBody))
              .andReturn();
      assertEquals(422, unresolvedConflict.getResponse().getStatus());
      assertTrue(
          unresolvedConflict
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_CONFLICT_UNRESOLVED"));

      List<GlossaryImportApplyService.RowResolution> resolvedRows =
          List.of(
              new GlossaryImportApplyService.RowResolution(
                  1, GlossaryImportApplyService.ResolutionAction.SKIP, null, null),
              new GlossaryImportApplyService.RowResolution(
                  2, GlossaryImportApplyService.ResolutionAction.SKIP, null, null),
              new GlossaryImportApplyService.RowResolution(
                  3,
                  GlossaryImportApplyService.ResolutionAction.MERGE,
                  secondTermId,
                  new GlossaryImportApplyService.ResolvedTerm(
                      "银剑",
                      "Kiếm bạc",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of("Silver Blade"))),
              new GlossaryImportApplyService.RowResolution(
                  4,
                  GlossaryImportApplyService.ResolutionAction.ADD,
                  null,
                  new GlossaryImportApplyService.ResolvedTerm(
                      "丹田",
                      "Đan điền",
                      0,
                      GlossaryMatcher.CasePolicy.INSENSITIVE,
                      GlossaryMatcher.MatchMode.WHOLE_PHRASE,
                      false,
                      null,
                      List.of())));
      String resolvedApplyBody =
          objectMapper.writeValueAsString(
              new GlossaryImportApplyController.ImportApplyRequest(
                  "JSON",
                  Base64.getEncoder().encodeToString(importJson.getBytes(StandardCharsets.UTF_8)),
                  resolvedRows));
      MvcResult applied =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "glossary-apply-shared-key-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(resolvedApplyBody))
              .andReturn();
      assertEquals(201, applied.getResponse().getStatus());
      assertEquals("\"v5\"", applied.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(applied.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode appliedBody = objectMapper.readTree(applied.getResponse().getContentAsString());
      assertEquals(false, appliedBody.path("replayed").asBoolean());
      assertEquals(3, appliedBody.path("draft").path("revisionNumber").asInt());
      assertEquals(
          latestDraft.revisionId().toString(),
          appliedBody.path("draft").path("parentRevisionId").asText());
      assertEquals(2, appliedBody.path("skippedRows").asInt());
      assertEquals(
          secondTermId.toString(), appliedBody.path("rows").get(2).path("termId").asText());
      UUID addedTermId = UUID.fromString(appliedBody.path("rows").get(3).path("termId").asText());

      MvcResult duplicateRetry =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "glossary-apply-shared-key-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(resolvedApplyBody))
              .andReturn();
      assertEquals(201, duplicateRetry.getResponse().getStatus());
      assertEquals("\"v5\"", duplicateRetry.getResponse().getHeader(HttpHeaders.ETAG));
      JsonNode replayedBody =
          objectMapper.readTree(duplicateRetry.getResponse().getContentAsString());
      assertEquals(true, replayedBody.path("replayed").asBoolean());
      assertEquals(
          appliedBody.path("draft").path("revisionId").asText(),
          replayedBody.path("draft").path("revisionId").asText());

      MvcResult reusedKey =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "glossary-apply-shared-key-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(resolvedApplyBody + " "))
              .andReturn();
      assertEquals(409, reusedKey.getResponse().getStatus());
      assertTrue(reusedKey.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      MvcResult staleApply =
          mockMvc
              .perform(
                  post(applyRoute, glossaryId)
                      .cookie(editorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, editorCsrf)
                      .header(HttpHeaders.IF_MATCH, "\"v4\"")
                      .header("Idempotency-Key", "00000000-0000-4000-8000-000000000002")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(resolvedApplyBody))
              .andReturn();
      assertEquals(409, staleApply.getResponse().getStatus());
      assertEquals("\"v5\"", staleApply.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(staleApply.getResponse().getContentAsString().contains("GLOSSARY_VERSION_STALE"));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  """
                      SELECT g.version, g.active_revision_id,
                             (SELECT count(*) FROM glossary_revisions r WHERE r.glossary_id = g.id) AS revisions,
                             (SELECT count(*) FROM glossary_terms t JOIN glossary_revisions r ON r.id = t.revision_id WHERE r.glossary_id = g.id) AS terms,
                             (SELECT count(*) FROM glossary_import_apply_idempotency i WHERE i.glossary_id = g.id) AS import_receipts,
                             (SELECT count(*) FROM glossary_import_apply_rows i WHERE i.glossary_id = g.id) AS import_rows
                      FROM glossaries g WHERE g.id = ?
                      """)) {
        select.setObject(1, glossaryId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals(5, row.getLong("version"));
          assertEquals(draft.revisionId(), row.getObject("active_revision_id", UUID.class));
          assertEquals(3, row.getLong("revisions"));
          assertEquals(9, row.getLong("terms"));
          assertEquals(1, row.getLong("import_receipts"));
          assertEquals(4, row.getLong("import_rows"));
        }
        try (PreparedStatement stableId =
            connection.prepareStatement(
                "SELECT count(*) FROM glossary_terms WHERE id = ? AND revision_id IN (?, ?, "
                    + "(SELECT id FROM glossary_revisions WHERE glossary_id = ? "
                    + "ORDER BY revision_no DESC LIMIT 1))")) {
          stableId.setObject(1, secondTermId);
          stableId.setObject(2, draft.revisionId());
          stableId.setObject(3, latestDraft.revisionId());
          stableId.setObject(4, glossaryId);
          try (ResultSet row = stableId.executeQuery()) {
            assertTrue(row.next());
            assertEquals(3, row.getInt(1));
          }
        }
        try (PreparedStatement newTerm =
            connection.prepareStatement("SELECT source_term FROM glossary_terms WHERE id = ?")) {
          newTerm.setObject(1, addedTermId);
          try (ResultSet row = newTerm.executeQuery()) {
            assertTrue(row.next());
            assertEquals("丹田", row.getString(1));
          }
        }
        select.setObject(1, emptyGlossaryId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals(1, row.getLong("version"));
          assertNull(row.getObject("active_revision_id"));
          assertEquals(0, row.getLong("revisions"));
        }
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement eraseMembership =
              connection.prepareStatement("DELETE FROM workspace_members WHERE user_id = ?")) {
        eraseMembership.setObject(1, editorId);
        eraseMembership.executeUpdate();
      }
      deleteSessionTestUser(editorId);
      try (Connection connection = dataSource.getConnection();
          PreparedStatement erasure =
              connection.prepareStatement(
                  "SELECT (SELECT count(*) FROM glossaries WHERE id = ?) AS glossary_count, "
                      + "(SELECT count(*) FROM glossary_revisions WHERE glossary_id = ?) AS revision_count, "
                      + "(SELECT count(*) FROM glossary_import_apply_idempotency "
                      + "WHERE glossary_id = ?) AS receipt_count, "
                      + "(SELECT count(*) FROM glossary_import_apply_rows WHERE glossary_id = ?) "
                      + "AS row_count")) {
        erasure.setObject(1, glossaryId);
        erasure.setObject(2, glossaryId);
        erasure.setObject(3, glossaryId);
        erasure.setObject(4, glossaryId);
        try (ResultSet row = erasure.executeQuery()) {
          assertTrue(row.next());
          assertEquals(1, row.getLong("glossary_count"));
          assertEquals(3, row.getLong("revision_count"));
          assertEquals(0, row.getLong("receipt_count"));
          assertEquals(0, row.getLong("row_count"));
        }
      }
    } finally {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM glossaries WHERE id IN (?, ?)")) {
          delete.setObject(1, glossaryId);
          delete.setObject(2, emptyGlossaryId);
          delete.executeUpdate();
        }
        if (libraryId != null) {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
            delete.setObject(1, libraryId);
            delete.executeUpdate();
          }
        }
        if (workspaceId != null) {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM workspaces WHERE id = ?")) {
            delete.setObject(1, workspaceId);
            delete.executeUpdate();
          }
        }
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(blockedId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void glossaryCatalogRoutesCreateAllScopesAndPaginateOnlyVisibleRecords() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Catalog Owner");
    UUID outsiderId = createSessionTestUser("Glossary Catalog Outsider");
    UUID blockedId = createSessionTestUser("Glossary Catalog Policy Blocked");
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID volumeId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());

      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement insertLibrary =
            connection.prepareStatement(
                "INSERT INTO libraries (id, owner_user_id, name, is_default)"
                    + " VALUES (?, ?, 'Glossary catalog fixture', FALSE)")) {
          insertLibrary.setObject(1, libraryId);
          insertLibrary.setObject(2, ownerId);
          assertEquals(1, insertLibrary.executeUpdate());
        }
        try (PreparedStatement insertWork =
            connection.prepareStatement(
                "INSERT INTO works (id, library_id, owner_user_id, title) VALUES (?, ?, ?, ?)")) {
          insertWork.setObject(1, workId);
          insertWork.setObject(2, libraryId);
          insertWork.setObject(3, ownerId);
          insertWork.setString(4, "Glossary catalog work");
          assertEquals(1, insertWork.executeUpdate());
        }
        try (PreparedStatement insertVolume =
            connection.prepareStatement(
                "INSERT INTO volumes (id, work_id, ordinal, title) VALUES (?, ?, 1, 'Volume')")) {
          insertVolume.setObject(1, volumeId);
          insertVolume.setObject(2, workId);
          assertEquals(1, insertVolume.executeUpdate());
        }
        try (PreparedStatement insertChapter =
            connection.prepareStatement(
                "INSERT INTO chapters (id, work_id, volume_id, ordinal, title)"
                    + " VALUES (?, ?, ?, 1, 'Chapter')")) {
          insertChapter.setObject(1, chapterId);
          insertChapter.setObject(2, workId);
          insertChapter.setObject(3, volumeId);
          assertEquals(1, insertChapter.executeUpdate());
        }
      }

      String route = "/api/v1/glossaries";
      MvcResult unauthenticated = mockMvc.perform(get(route)).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());
      MvcResult policyBlocked = mockMvc.perform(get(route).cookie(blockedCookie)).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      GlossaryCatalogController.CreateGlossaryRequest globalRequest =
          new GlossaryCatalogController.CreateGlossaryRequest(
              "Wuxia Global",
              GlossaryMatcher.Scope.USER_GLOBAL,
              "zh-Hans",
              "vi",
              null,
              null,
              null,
              null);
      String globalJson = objectMapper.writeValueAsString(globalRequest);
      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(route)
                      .cookie(ownerCookie)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(globalJson))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult blockedCreate =
          mockMvc
              .perform(
                  post(route)
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(globalJson))
              .andReturn();
      assertEquals(403, blockedCreate.getResponse().getStatus());

      MvcResult createdGlobal =
          mockMvc
              .perform(
                  post(route)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(globalJson))
              .andReturn();
      assertEquals(201, createdGlobal.getResponse().getStatus());
      assertEquals("\"v1\"", createdGlobal.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(
          createdGlobal.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode globalBody = objectMapper.readTree(createdGlobal.getResponse().getContentAsString());
      assertEquals("USER_GLOBAL", globalBody.path("scope").asText());
      assertEquals(1, globalBody.path("version").asInt());
      assertTrue(globalBody.path("activeRevisionId").isNull());
      assertTrue(globalBody.path("canWrite").asBoolean());

      List<GlossaryCatalogController.CreateGlossaryRequest> scopedRequests =
          List.of(
              new GlossaryCatalogController.CreateGlossaryRequest(
                  "Wuxia Library",
                  GlossaryMatcher.Scope.LIBRARY,
                  "zh-Hans",
                  "vi",
                  libraryId.toString(),
                  null,
                  null,
                  null),
              new GlossaryCatalogController.CreateGlossaryRequest(
                  "Wuxia Work",
                  GlossaryMatcher.Scope.WORK,
                  "zh-Hans",
                  "vi",
                  libraryId.toString(),
                  workId.toString(),
                  null,
                  null),
              new GlossaryCatalogController.CreateGlossaryRequest(
                  "Wuxia Volume",
                  GlossaryMatcher.Scope.VOLUME,
                  "zh-Hans",
                  "vi",
                  libraryId.toString(),
                  workId.toString(),
                  volumeId.toString(),
                  null),
              new GlossaryCatalogController.CreateGlossaryRequest(
                  "Wuxia Chapter",
                  GlossaryMatcher.Scope.CHAPTER,
                  "zh-Hans",
                  "vi",
                  libraryId.toString(),
                  workId.toString(),
                  null,
                  chapterId.toString()));
      for (GlossaryCatalogController.CreateGlossaryRequest scopedRequest : scopedRequests) {
        MvcResult created =
            mockMvc
                .perform(
                    post(route)
                        .cookie(ownerCookie)
                        .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scopedRequest)))
                .andReturn();
        assertEquals(201, created.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertEquals(scopedRequest.scope().name(), body.path("scope").asText());
        assertEquals("\"v1\"", created.getResponse().getHeader(HttpHeaders.ETAG));
      }

      GlossaryCatalogController.CreateGlossaryRequest invalidScopeTarget =
          new GlossaryCatalogController.CreateGlossaryRequest(
              "Bad Wuxia",
              GlossaryMatcher.Scope.WORK,
              "zh-Hans",
              "vi",
              libraryId.toString(),
              null,
              null,
              null);
      MvcResult invalidTarget =
          mockMvc
              .perform(
                  post(route)
                      .cookie(ownerCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(invalidScopeTarget)))
              .andReturn();
      assertEquals(400, invalidTarget.getResponse().getStatus());

      GlossaryCatalogController.CreateGlossaryRequest outsiderRequest =
          new GlossaryCatalogController.CreateGlossaryRequest(
              "Outsider Wuxia",
              GlossaryMatcher.Scope.LIBRARY,
              "zh-Hans",
              "vi",
              libraryId.toString(),
              null,
              null,
              null);
      MvcResult outsiderCreate =
          mockMvc
              .perform(
                  post(route)
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(outsiderRequest)))
              .andReturn();
      assertEquals(404, outsiderCreate.getResponse().getStatus());

      MvcResult firstPage =
          mockMvc
              .perform(get(route).cookie(ownerCookie).param("q", "Wuxia").param("limit", "2"))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(2, firstBody.path("items").size());
      String cursor = firstBody.path("nextCursor").asText();
      assertFalse(cursor.isBlank());

      MvcResult secondPage =
          mockMvc
              .perform(
                  get(route)
                      .cookie(ownerCookie)
                      .param("q", "wuxia")
                      .param("limit", "2")
                      .param("cursor", cursor))
              .andReturn();
      assertEquals(200, secondPage.getResponse().getStatus());
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(2, secondBody.path("items").size());
      String lastCursor = secondBody.path("nextCursor").asText();
      assertFalse(lastCursor.isBlank());

      MvcResult thirdPage =
          mockMvc
              .perform(
                  get(route)
                      .cookie(ownerCookie)
                      .param("q", "wuxia")
                      .param("limit", "2")
                      .param("cursor", lastCursor))
              .andReturn();
      assertEquals(200, thirdPage.getResponse().getStatus());
      JsonNode thirdBody = objectMapper.readTree(thirdPage.getResponse().getContentAsString());
      assertEquals(1, thirdBody.path("items").size());
      assertTrue(thirdBody.path("nextCursor").isNull());

      MvcResult filterMismatch =
          mockMvc
              .perform(
                  get(route).cookie(ownerCookie).param("q", "different").param("cursor", cursor))
              .andReturn();
      assertEquals(400, filterMismatch.getResponse().getStatus());
      MvcResult tamperedCursor =
          mockMvc
              .perform(
                  get(route)
                      .cookie(ownerCookie)
                      .param("q", "wuxia")
                      .param("cursor", tamperLastCharacter(cursor)))
              .andReturn();
      assertEquals(400, tamperedCursor.getResponse().getStatus());

      MvcResult workFilter =
          mockMvc
              .perform(
                  get(route)
                      .cookie(ownerCookie)
                      .param("scope", "WORK")
                      .param("libraryId", libraryId.toString()))
              .andReturn();
      assertEquals(200, workFilter.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(workFilter.getResponse().getContentAsString())
              .path("items")
              .size());

      MvcResult outsiderList = mockMvc.perform(get(route).cookie(outsiderCookie)).andReturn();
      assertEquals(200, outsiderList.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(outsiderList.getResponse().getContentAsString())
              .path("items")
              .size());
    } finally {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement deleteGlossaries =
            connection.prepareStatement(
                "DELETE FROM glossaries WHERE owner_user_id = ? OR library_id = ?")) {
          deleteGlossaries.setObject(1, ownerId);
          deleteGlossaries.setObject(2, libraryId);
          deleteGlossaries.executeUpdate();
        }
        try (PreparedStatement deleteChapters =
            connection.prepareStatement("DELETE FROM chapters WHERE work_id = ?")) {
          deleteChapters.setObject(1, workId);
          deleteChapters.executeUpdate();
        }
        try (PreparedStatement deleteVolumes =
            connection.prepareStatement("DELETE FROM volumes WHERE work_id = ?")) {
          deleteVolumes.setObject(1, workId);
          deleteVolumes.executeUpdate();
        }
        try (PreparedStatement deleteWorks =
            connection.prepareStatement("DELETE FROM works WHERE id = ?")) {
          deleteWorks.setObject(1, workId);
          deleteWorks.executeUpdate();
        }
        try (PreparedStatement deleteLibrary =
            connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
          deleteLibrary.setObject(1, libraryId);
          deleteLibrary.executeUpdate();
        }
      }
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(blockedId);
    }
  }

  private static String tamperLastCharacter(String token) {
    char last = token.charAt(token.length() - 1);
    return token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
  }

  @Test
  void glossaryCatalogReportsCurrentWorkspaceWriteCapabilityToEachActor() throws Exception {
    UUID ownerId = createSessionTestUser("Glossary Capability Owner");
    UUID editorId = createSessionTestUser("Glossary Capability Editor");
    UUID readerId = createSessionTestUser("Glossary Capability Reader");
    UUID outsiderId = createSessionTestUser("Glossary Capability Outsider");
    UUID blockedId = createSessionTestUser("Glossary Capability Policy Blocked");
    UUID workspaceId = null;
    UUID libraryId = null;
    UUID glossaryId = UUID.randomUUID();
    UUID activeRevisionId = UUID.randomUUID();
    UUID latestRevisionId = UUID.randomUUID();
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(editorId);
      addCurrentPolicyAcceptances(readerId);
      addCurrentPolicyAcceptances(outsiderId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession editorSession = sessionService.create(editorId).orElseThrow();
      SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      SessionService.IssuedSession blockedSession = sessionService.create(blockedId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      var editorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", editorSession.cookieValue());
      var readerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", readerSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());

      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Glossary Capability Workspace");
      workspaceId = workspace.id();
      workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      libraryId =
          libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Capability Library").id();
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO glossaries (id, name, scope_type, library_id, source_language,"
                      + " target_language) VALUES (?, 'Shared Capability Glossary', 'LIBRARY', ?,"
                      + " 'zh-Hans', 'vi')")) {
        insert.setObject(1, glossaryId);
        insert.setObject(2, libraryId);
        assertEquals(1, insert.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO glossary_revisions (id, glossary_id, revision_no, created_by_user_id)"
                      + " VALUES (?, ?, 1, ?)")) {
        insert.setObject(1, activeRevisionId);
        insert.setObject(2, glossaryId);
        insert.setObject(3, ownerId);
        assertEquals(1, insert.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement activate =
              connection.prepareStatement(
                  "UPDATE glossaries SET active_revision_id = ?, version = 2 WHERE id = ?")) {
        activate.setObject(1, activeRevisionId);
        activate.setObject(2, glossaryId);
        assertEquals(1, activate.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO glossary_revisions"
                      + " (id, glossary_id, revision_no, parent_revision_id, created_by_user_id)"
                      + " VALUES (?, ?, 2, ?, ?)")) {
        insert.setObject(1, latestRevisionId);
        insert.setObject(2, glossaryId);
        insert.setObject(3, activeRevisionId);
        insert.setObject(4, editorId);
        assertEquals(1, insert.executeUpdate());
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement updateVersion =
              connection.prepareStatement("UPDATE glossaries SET version = 3 WHERE id = ?")) {
        updateVersion.setObject(1, glossaryId);
        assertEquals(1, updateVersion.executeUpdate());
      }

      JsonNode ownerItem = catalogItemForLibrary(ownerCookie, libraryId);
      JsonNode editorItem = catalogItemForLibrary(editorCookie, libraryId);
      JsonNode readerItem = catalogItemForLibrary(readerCookie, libraryId);
      assertEquals(glossaryId.toString(), ownerItem.path("id").asText());
      assertEquals(glossaryId.toString(), editorItem.path("id").asText());
      assertEquals(glossaryId.toString(), readerItem.path("id").asText());
      assertTrue(ownerItem.path("canWrite").asBoolean());
      assertTrue(editorItem.path("canWrite").asBoolean());
      assertFalse(readerItem.path("canWrite").asBoolean());
      assertEquals(1, ownerItem.path("activeRevisionNumber").asInt());
      assertEquals(2, ownerItem.path("latestRevisionNumber").asInt());
      assertTrue(readerItem.path("latestRevisionNumber").isNull());

      JsonNode ownerMetadata = glossaryMetadataForActor(ownerCookie, glossaryId);
      JsonNode editorMetadata = glossaryMetadataForActor(editorCookie, glossaryId);
      JsonNode readerMetadata = glossaryMetadataForActor(readerCookie, glossaryId);
      assertTrue(ownerMetadata.path("canWrite").asBoolean());
      assertTrue(editorMetadata.path("canWrite").asBoolean());
      assertFalse(readerMetadata.path("canWrite").asBoolean());
      assertEquals(1, ownerMetadata.path("activeRevisionNumber").asInt());
      assertEquals(2, ownerMetadata.path("latestRevisionNumber").asInt());
      assertEquals(1, readerMetadata.path("activeRevisionNumber").asInt());
      assertTrue(readerMetadata.path("latestRevisionNumber").isNull());
      assertEquals(
          401,
          mockMvc
              .perform(get("/api/v1/glossaries/" + glossaryId))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          403,
          mockMvc
              .perform(get("/api/v1/glossaries/" + glossaryId).cookie(blockedCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          404,
          mockMvc
              .perform(get("/api/v1/glossaries/" + glossaryId).cookie(outsiderCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          400,
          mockMvc
              .perform(get("/api/v1/glossaries/not-a-uuid").cookie(ownerCookie))
              .andReturn()
              .getResponse()
              .getStatus());
    } finally {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement deleteGlossary =
            connection.prepareStatement("DELETE FROM glossaries WHERE id = ?")) {
          deleteGlossary.setObject(1, glossaryId);
          deleteGlossary.executeUpdate();
        }
        if (libraryId != null) {
          try (PreparedStatement deleteLibrary =
              connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
            deleteLibrary.setObject(1, libraryId);
            deleteLibrary.executeUpdate();
          }
        }
      }
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(blockedId);
    }
  }

  private JsonNode catalogItemForLibrary(jakarta.servlet.http.Cookie actorCookie, UUID libraryId)
      throws Exception {
    MvcResult response =
        mockMvc
            .perform(
                get("/api/v1/glossaries")
                    .cookie(actorCookie)
                    .param("libraryId", libraryId.toString()))
            .andReturn();
    assertEquals(200, response.getResponse().getStatus());
    JsonNode items =
        objectMapper.readTree(response.getResponse().getContentAsString()).path("items");
    assertEquals(1, items.size());
    return items.get(0);
  }

  private JsonNode glossaryMetadataForActor(
      jakarta.servlet.http.Cookie actorCookie, UUID glossaryId) throws Exception {
    MvcResult response =
        mockMvc.perform(get("/api/v1/glossaries/" + glossaryId).cookie(actorCookie)).andReturn();
    assertEquals(200, response.getResponse().getStatus());
    assertTrue(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    JsonNode metadata = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals(glossaryId.toString(), metadata.path("id").asText());
    assertEquals(
        "\"v" + metadata.path("version").asText() + "\"",
        response.getResponse().getHeader(HttpHeaders.ETAG));
    return metadata;
  }

  @Test
  void cultivationProfileIsPrivateVersionPinnedIdempotentAndCsrfProtected() throws Exception {
    UUID actorId = createSessionTestUser("Cultivation Profile Owner");
    UUID otherUserId = createSessionTestUser("Other Cultivation Profile");
    UUID policyBlockedId = createSessionTestUser("Cultivation Policy Blocked");
    try {
      addCurrentPolicyAcceptances(actorId);
      addCurrentPolicyAcceptances(otherUserId);
      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
      SessionService.IssuedSession blockedSession =
          sessionService.create(policyBlockedId).orElseThrow();
      var actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      var otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());

      MvcResult unauthenticated = mockMvc.perform(get("/api/v1/me/cultivation")).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult initial =
          mockMvc.perform(get("/api/v1/me/cultivation").cookie(actorCookie)).andReturn();
      assertEquals(200, initial.getResponse().getStatus());
      assertTrue(initial.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode initialBody = objectMapper.readTree(initial.getResponse().getContentAsString());
      assertFalse(initialBody.path("enabled").asBoolean());
      assertEquals(0, initialBody.path("profileVersion").asLong());
      assertEquals(0, initialBody.path("totalXp").asLong());
      assertEquals("cultivation-v1", initialBody.path("rankCatalogVersion").asText());
      assertEquals("mortal", initialBody.path("currentRank").path("key").asText());
      assertEquals(100, initialBody.path("xpToNextRank").asLong());
      assertEquals(120, initialBody.path("dailyXpCap").asInt());
      assertEquals(18, initialBody.path("rankCatalog").size());
      assertFalse(initialBody.has("userId"));
      assertFalse(initialBody.toString().contains("Cultivation Profile Owner"));

      MvcResult otherProfile =
          mockMvc.perform(get("/api/v1/me/cultivation").cookie(otherCookie)).andReturn();
      JsonNode otherBody = objectMapper.readTree(otherProfile.getResponse().getContentAsString());
      assertEquals(200, otherProfile.getResponse().getStatus());
      assertFalse(otherBody.path("enabled").asBoolean());
      assertEquals(0, otherBody.path("profileVersion").asLong());

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  put("/api/v1/me/cultivation/settings")
                      .cookie(actorCookie)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"enabled\":true}"))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult unknownField =
          mockMvc
              .perform(
                  put("/api/v1/me/cultivation/settings")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"enabled\":true,\"totalXp\":999999}"))
              .andReturn();
      assertEquals(400, unknownField.getResponse().getStatus());

      MvcResult enabled =
          mockMvc
              .perform(
                  put("/api/v1/me/cultivation/settings")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"enabled\":true}"))
              .andReturn();
      assertEquals(200, enabled.getResponse().getStatus());
      JsonNode enabledBody = objectMapper.readTree(enabled.getResponse().getContentAsString());
      assertTrue(enabledBody.path("enabled").asBoolean());
      assertEquals(1, enabledBody.path("profileVersion").asLong());

      MvcResult repeatedEnable =
          mockMvc
              .perform(
                  put("/api/v1/me/cultivation/settings")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"enabled\":true}"))
              .andReturn();
      JsonNode repeatedBody =
          objectMapper.readTree(repeatedEnable.getResponse().getContentAsString());
      assertEquals(200, repeatedEnable.getResponse().getStatus());
      assertEquals(1, repeatedBody.path("profileVersion").asLong());

      MvcResult paused =
          mockMvc
              .perform(
                  put("/api/v1/me/cultivation/settings")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"enabled\":false}"))
              .andReturn();
      JsonNode pausedBody = objectMapper.readTree(paused.getResponse().getContentAsString());
      assertEquals(200, paused.getResponse().getStatus());
      assertFalse(pausedBody.path("enabled").asBoolean());
      assertEquals(2, pausedBody.path("profileVersion").asLong());
      assertEquals("cultivation-v1", pausedBody.path("rankCatalogVersion").asText());

      MvcResult policyBlocked =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", blockedSession.cookieValue())))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      SQLException immutableCatalog =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE cultivation_rank_catalog_entries SET threshold_xp = 101 WHERE rank_key = 'qi-1'")) {
                  update.executeUpdate();
                }
              });
      assertEquals("55000", immutableCatalog.getSQLState());
    } finally {
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(otherUserId);
      deleteSessionTestUser(policyBlockedId);
    }
  }

  @Test
  void cultivationActivityIsPrivateDailyXpOnlyAndUsesAccountBoundPages() throws Exception {
    UUID actorId = createSessionTestUser("Cultivation Activity Owner");
    UUID otherUserId = createSessionTestUser("Cultivation Activity Other Owner");
    UUID policyBlockedId = createSessionTestUser("Cultivation Activity Policy Blocked");
    ReaderHistoryFixture readable =
        createReaderHistoryFixture(
            actorId, "ACTIVITY_PRIVATE_WORK_SENTINEL", "ACTIVITY_PRIVATE_TEXT_SENTINEL");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<LocalDate> activityDates =
        List.of(today.minusDays(1), today.minusDays(3), today.minusDays(5));
    try {
      addCurrentPolicyAcceptances(actorId);
      addCurrentPolicyAcceptances(otherUserId);
      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
      var actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      var otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      cultivationRepository.setEnabled(actorId, true);
      cultivationRepository.setEnabled(otherUserId, true);

      for (int index = 0; index < activityDates.size(); index++) {
        LocalDate activityDate = activityDates.get(index);
        UUID sessionId = UUID.randomUUID();
        Instant activityAt = activityDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
        insertCultivationTestReadingSession(
            actorId,
            sessionId,
            readable,
            activityAt.minusSeconds(300),
            "cultivation-activity-session-" + index + "-0001");
        insertCultivationXpLedger(actorId, sessionId, 0, activityAt);
        closeCultivationTestReadingSession(sessionId);
        rebuildCultivationDailyXp(actorId, activityDate);
      }

      MvcResult unauthenticated =
          mockMvc.perform(get("/api/v1/me/cultivation/activity")).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult firstPage =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation/activity").cookie(actorCookie).param("limit", "2"))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(2, firstBody.path("items").size());
      assertEquals(
          activityDates.get(0).toString(),
          firstBody.path("items").get(0).path("activityDate").asText());
      assertEquals(1, firstBody.path("items").get(0).path("xpEarned").asInt());
      assertTrue(firstBody.path("nextCursor").isTextual());
      assertFalse(firstBody.toString().contains("ACTIVITY_PRIVATE_WORK_SENTINEL"));
      assertFalse(firstBody.toString().contains("ACTIVITY_PRIVATE_TEXT_SENTINEL"));
      assertFalse(firstBody.toString().contains("workId"));
      assertFalse(firstBody.toString().contains("chapterId"));

      String nextCursor = firstBody.path("nextCursor").asText();
      MvcResult secondPage =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation/activity")
                      .cookie(actorCookie)
                      .param("cursor", nextCursor)
                      .param("limit", "2"))
              .andReturn();
      assertEquals(200, secondPage.getResponse().getStatus());
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(1, secondBody.path("items").size());
      assertEquals(
          activityDates.get(2).toString(),
          secondBody.path("items").get(0).path("activityDate").asText());
      assertTrue(secondBody.path("nextCursor").isNull());

      MvcResult crossAccountCursor =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation/activity")
                      .cookie(otherCookie)
                      .param("cursor", nextCursor))
              .andReturn();
      assertEquals(400, crossAccountCursor.getResponse().getStatus());
      assertTrue(
          crossAccountCursor
              .getResponse()
              .getContentAsString()
              .contains("INVALID_CULTIVATION_ACTIVITY_CURSOR"));

      MvcResult invalidLimit =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation/activity").cookie(actorCookie).param("limit", "101"))
              .andReturn();
      assertEquals(400, invalidLimit.getResponse().getStatus());

      SessionService.IssuedSession blockedSession =
          sessionService.create(policyBlockedId).orElseThrow();
      MvcResult policyBlocked =
          mockMvc
              .perform(
                  get("/api/v1/me/cultivation/activity")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", blockedSession.cookieValue())))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
    } finally {
      deleteReaderHistoryFixtures(actorId, List.of(readable));
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(otherUserId);
      deleteSessionTestUser(policyBlockedId);
    }
  }

  @Test
  void cultivationXpLedgerIsAppendOnlyIdempotentAndDailyProjectionRebuilds() throws Exception {
    UUID actorId = createSessionTestUser("Cultivation XP Ledger Owner");
    UUID disabledActorId = createSessionTestUser("Cultivation XP Ledger Disabled");
    UUID firstSessionId = UUID.randomUUID();
    UUID secondSessionId = UUID.randomUUID();
    LocalDate firstDay = LocalDate.of(2026, 10, 31);
    LocalDate secondDay = LocalDate.of(2026, 11, 1);
    ReaderHistoryFixture readable =
        createReaderHistoryFixture(actorId, "Cultivation XP ledger work", "XP_LEDGER_BODY");
    try {
      cultivationRepository.setEnabled(actorId, true);
      insertCultivationTestReadingSession(
          actorId,
          firstSessionId,
          readable,
          Instant.parse("2026-10-31T00:00:00Z"),
          "xp-ledger-first-session-0001");
      UUID firstEntryId =
          insertCultivationXpLedger(
              actorId, firstSessionId, 0, Instant.parse("2026-10-31T23:59:00Z"));
      insertCultivationXpLedger(actorId, firstSessionId, 1, Instant.parse("2026-11-01T00:01:00Z"));

      SQLException duplicateMinute =
          assertThrows(
              SQLException.class,
              () ->
                  insertCultivationXpLedger(
                      actorId, firstSessionId, 0, Instant.parse("2026-10-31T23:59:30Z")));
      assertEquals("23505", duplicateMinute.getSQLState());

      closeCultivationTestReadingSession(firstSessionId);
      insertCultivationTestReadingSession(
          actorId,
          secondSessionId,
          readable,
          Instant.parse("2026-10-31T00:00:00Z"),
          "xp-ledger-second-session-0001");
      insertCultivationXpLedger(actorId, secondSessionId, 0, Instant.parse("2026-10-31T22:30:00Z"));

      SQLException missingSourceSession =
          assertThrows(
              SQLException.class,
              () ->
                  insertCultivationXpLedger(
                      actorId, UUID.randomUUID(), 0, Instant.parse("2026-10-31T21:00:00Z")));
      assertEquals("55000", missingSourceSession.getSQLState());

      SQLException immutableUpdate =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE cultivation_xp_ledger SET xp_delta = 2 WHERE id = ?")) {
                  update.setObject(1, firstEntryId);
                  update.executeUpdate();
                }
              });
      assertEquals("55000", immutableUpdate.getSQLState());

      SQLException immutableDelete =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement delete =
                        connection.prepareStatement(
                            "DELETE FROM cultivation_xp_ledger WHERE id = ?")) {
                  delete.setObject(1, firstEntryId);
                  delete.executeUpdate();
                }
              });
      assertEquals("55000", immutableDelete.getSQLState());

      assertEquals(firstDay, cultivationXpActivityDate(firstEntryId));
      rebuildCultivationDailyXp(actorId, firstDay);
      assertEquals(2L, cultivationDailyXp(actorId, firstDay));
      assertEquals(2L, cultivationDailyLedgerCount(actorId, firstDay));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement corruptProjection =
              connection.prepareStatement(
                  "UPDATE cultivation_daily_xp_aggregates SET xp_earned = 999 "
                      + "WHERE user_id = ? AND activity_date = ?")) {
        corruptProjection.setObject(1, actorId);
        corruptProjection.setObject(2, firstDay);
        assertEquals(1, corruptProjection.executeUpdate());
      }
      rebuildCultivationDailyXp(actorId, firstDay);
      assertEquals(2L, cultivationDailyXp(actorId, firstDay));

      rebuildCultivationDailyXp(actorId, secondDay);
      assertEquals(1L, cultivationDailyXp(actorId, secondDay));
      try (Connection connection = dataSource.getConnection();
          PreparedStatement staleProjection =
              connection.prepareStatement(
                  "INSERT INTO cultivation_daily_xp_aggregates "
                      + "(user_id, activity_date, xp_earned, ledger_entry_count) "
                      + "VALUES (?, DATE '2026-11-02', 1, 1)")) {
        staleProjection.setObject(1, actorId);
        assertEquals(1, staleProjection.executeUpdate());
      }
      rebuildCultivationDailyXp(actorId, LocalDate.of(2026, 11, 2));
      assertEquals(0L, cultivationDailyXp(actorId, LocalDate.of(2026, 11, 2)));

      SQLException disabledProfile =
          assertThrows(
              SQLException.class,
              () ->
                  insertCultivationXpLedger(
                      disabledActorId,
                      UUID.randomUUID(),
                      0,
                      Instant.parse("2026-10-31T12:00:00Z")));
      assertEquals("55000", disabledProfile.getSQLState());
    } finally {
      deleteReaderHistoryFixtures(actorId, List.of(readable));
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(disabledActorId);
    }
  }

  @Test
  void cultivationReadingSessionBoundsActiveTimeAndProtectsLifecycle() throws Exception {
    UUID actorId = createSessionTestUser("Cultivation Session Owner");
    UUID otherUserId = createSessionTestUser("Cultivation Session Other Owner");
    UUID policyBlockedId = createSessionTestUser("Cultivation Session Policy Blocked");
    ReaderHistoryFixture readable =
        createReaderHistoryFixture(actorId, "Cultivation session work", "SESSION_SOURCE_BODY");
    ReaderHistoryFixture foreign =
        createReaderHistoryFixture(otherUserId, "Foreign session work", "FOREIGN_SOURCE_BODY");
    try {
      addCurrentPolicyAcceptances(actorId);
      addCurrentPolicyAcceptances(otherUserId);
      cultivationRepository.setEnabled(actorId, true);
      cultivationRepository.setEnabled(otherUserId, true);
      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
      SessionService.IssuedSession blockedSession =
          sessionService.create(policyBlockedId).orElseThrow();
      var actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      var blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      var otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      String otherCsrf = csrfTokenService.tokenFor(otherSession.cookieValue());

      String startBody = readingSessionStartBody(readable);
      MvcResult otherOwnerSession =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf)
                      .header("Idempotency-Key", "other-owner-session-start-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(readingSessionStartBody(foreign)))
              .andReturn();
      UUID otherOwnerSessionId =
          UUID.fromString(
              objectMapper
                  .readTree(otherOwnerSession.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText());
      MvcResult crossUserHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              otherOwnerSessionId,
              "session-heartbeat-foreign-owner-0001",
              "{\"sequence\":1,\"active\":true}");
      assertEquals(404, crossUserHeartbeat.getResponse().getStatus());

      MvcResult unauthenticated =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .header("Idempotency-Key", "session-start-anonymous-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(403, unauthenticated.getResponse().getStatus());
      assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header("Idempotency-Key", "session-start-no-csrf-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult blockedPolicy =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(blockedCookie)
                      .header(
                          SessionCsrfTokenService.TOKEN_HEADER_NAME,
                          csrfTokenService.tokenFor(blockedSession.cookieValue()))
                      .header("Idempotency-Key", "session-start-policy-blocked-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(403, blockedPolicy.getResponse().getStatus());
      assertTrue(
          blockedPolicy.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult wrongChapterOwner =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-foreign-work-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(readingSessionStartBody(foreign)))
              .andReturn();
      assertEquals(404, wrongChapterOwner.getResponse().getStatus());

      MvcResult unknownStartValue =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-client-duration-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          startBody.substring(0, startBody.length() - 1)
                              + ",\"elapsedSeconds\":3600}"))
              .andReturn();
      assertEquals(400, unknownStartValue.getResponse().getStatus());

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-idempotent-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(createdBody.path("sessionId").asText());
      assertEquals("active", createdBody.path("state").asText());
      assertEquals(30, createdBody.path("heartbeatIntervalSeconds").asInt());
      assertEquals(90, createdBody.path("idleTimeoutSeconds").asInt());
      assertFalse(createdBody.has("workId"));
      assertFalse(createdBody.has("chapterId"));
      assertFalse(createdBody.toString().contains("SESSION_SOURCE_BODY"));

      MvcResult replayedStart =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-idempotent-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(200, replayedStart.getResponse().getStatus());
      assertEquals("true", replayedStart.getResponse().getHeader("Idempotency-Replayed"));
      assertEquals(
          sessionId,
          UUID.fromString(
              objectMapper
                  .readTree(replayedStart.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText()));

      MvcResult changedStartReplay =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-idempotent-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(readingSessionStartBody(foreign)))
              .andReturn();
      assertEquals(409, changedStartReplay.getResponse().getStatus());

      MvcResult secondActiveSession =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-second-active-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(409, secondActiveSession.getResponse().getStatus());

      setCultivationReadingSessionAge(sessionId, 50, 600);
      String firstHeartbeatBody = "{\"sequence\":1,\"active\":true}";
      MvcResult firstHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-owner-0001",
              firstHeartbeatBody);
      assertEquals(200, firstHeartbeat.getResponse().getStatus());
      JsonNode firstHeartbeatBodyJson =
          objectMapper.readTree(firstHeartbeat.getResponse().getContentAsString());
      assertEquals(50, firstHeartbeatBodyJson.path("creditedSeconds").asInt());
      assertEquals(50, firstHeartbeatBodyJson.path("totalActiveSeconds").asLong());
      assertEquals(0, firstHeartbeatBodyJson.path("awardedXp").asInt());

      SQLException immutableHeartbeatEvent =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE cultivation_reading_session_events SET credited_seconds = 0 "
                                + "WHERE session_id = ? AND idempotency_key = ?")) {
                  update.setObject(1, sessionId);
                  update.setString(2, "session-heartbeat-owner-0001");
                  update.executeUpdate();
                }
              });
      assertEquals("55000", immutableHeartbeatEvent.getSQLState());

      MvcResult replayedHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-owner-0001",
              firstHeartbeatBody);
      assertEquals(200, replayedHeartbeat.getResponse().getStatus());
      assertEquals("true", replayedHeartbeat.getResponse().getHeader("Idempotency-Replayed"));
      assertEquals(
          50,
          objectMapper
              .readTree(replayedHeartbeat.getResponse().getContentAsString())
              .path("totalActiveSeconds")
              .asLong());

      MvcResult changedHeartbeatReplay =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-owner-0001",
              "{\"sequence\":1,\"active\":false}");
      assertEquals(409, changedHeartbeatReplay.getResponse().getStatus());

      MvcResult outOfOrder =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-out-of-order-0001",
              "{\"sequence\":3,\"active\":true}");
      assertEquals(409, outOfOrder.getResponse().getStatus());
      assertEquals(
          2,
          objectMapper
              .readTree(outOfOrder.getResponse().getContentAsString())
              .path("expectedSequence")
              .asInt());

      setCultivationReadingSessionAge(sessionId, 30, 600);
      MvcResult pauseSignal =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-pause-signal-0001",
              "{\"sequence\":2,\"active\":false}");
      JsonNode pauseSignalBody =
          objectMapper.readTree(pauseSignal.getResponse().getContentAsString());
      assertEquals(200, pauseSignal.getResponse().getStatus());
      assertEquals(30, pauseSignalBody.path("creditedSeconds").asInt());
      assertEquals(80, pauseSignalBody.path("totalActiveSeconds").asLong());
      assertEquals(1, pauseSignalBody.path("awardedXp").asInt());
      MvcResult replayedAwardingHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-pause-signal-0001",
              "{\"sequence\":2,\"active\":false}");
      assertEquals(
          "true", replayedAwardingHeartbeat.getResponse().getHeader("Idempotency-Replayed"));
      assertEquals(
          1,
          objectMapper
              .readTree(replayedAwardingHeartbeat.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());

      setCultivationReadingSessionAge(sessionId, 45, 600);
      MvcResult resumeSignal =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-resume-signal-0001",
              "{\"sequence\":3,\"active\":true}");
      JsonNode resumeSignalBody =
          objectMapper.readTree(resumeSignal.getResponse().getContentAsString());
      assertEquals(200, resumeSignal.getResponse().getStatus());
      assertEquals(0, resumeSignalBody.path("creditedSeconds").asInt());
      assertEquals(80, resumeSignalBody.path("totalActiveSeconds").asLong());

      setCultivationReadingSessionAge(sessionId, 30, 600);
      MvcResult activeAgain =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-active-again-0001",
              "{\"sequence\":4,\"active\":true}");
      assertEquals(200, activeAgain.getResponse().getStatus());
      assertEquals(
          30,
          objectMapper
              .readTree(activeAgain.getResponse().getContentAsString())
              .path("creditedSeconds")
              .asInt());
      assertEquals(
          0,
          objectMapper
              .readTree(activeAgain.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());

      setCultivationReadingSessionAge(sessionId, 30, 600);
      CountDownLatch heartbeatReady = new CountDownLatch(2);
      CountDownLatch heartbeatStart = new CountDownLatch(1);
      ExecutorService heartbeatExecutor = Executors.newFixedThreadPool(2);
      MvcResult concurrentActive;
      MvcResult concurrentPaused;
      try {
        Future<MvcResult> activeRequest =
            heartbeatExecutor.submit(
                () -> {
                  heartbeatReady.countDown();
                  if (!heartbeatStart.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent reading heartbeat did not start");
                  }
                  return postCultivationHeartbeat(
                      actorCookie,
                      actorCsrf,
                      sessionId,
                      "session-heartbeat-concurrent-active-0001",
                      "{\"sequence\":5,\"active\":true}");
                });
        Future<MvcResult> pausedRequest =
            heartbeatExecutor.submit(
                () -> {
                  heartbeatReady.countDown();
                  if (!heartbeatStart.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent reading heartbeat did not start");
                  }
                  return postCultivationHeartbeat(
                      actorCookie,
                      actorCsrf,
                      sessionId,
                      "session-heartbeat-concurrent-paused-0001",
                      "{\"sequence\":5,\"active\":false}");
                });
        assertTrue(heartbeatReady.await(10, TimeUnit.SECONDS));
        heartbeatStart.countDown();
        concurrentActive = activeRequest.get(10, TimeUnit.SECONDS);
        concurrentPaused = pausedRequest.get(10, TimeUnit.SECONDS);
      } finally {
        heartbeatExecutor.shutdownNow();
      }
      assertTrue(
          List.of(
                  concurrentActive.getResponse().getStatus(),
                  concurrentPaused.getResponse().getStatus())
              .containsAll(List.of(200, 409)));
      assertEquals(140, sessionActiveSeconds(sessionId));
      MvcResult winningHeartbeat =
          concurrentActive.getResponse().getStatus() == 200 ? concurrentActive : concurrentPaused;
      assertEquals(
          1,
          objectMapper
              .readTree(winningHeartbeat.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());
      setCultivationReadingSessionActiveAge(sessionId, 40, 600);

      MvcResult closed =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions/{sessionId}:close", sessionId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(200, closed.getResponse().getStatus());
      JsonNode closedBody = objectMapper.readTree(closed.getResponse().getContentAsString());
      assertEquals("closed", closedBody.path("state").asText());
      assertEquals(180, closedBody.path("totalActiveSeconds").asLong());
      assertEquals(1, closedBody.path("awardedXp").asInt());
      assertTrue(closedBody.path("closedAt").isTextual());
      assertEquals(3, cultivationRepository.find(actorId).totalXp());
      assertEquals(3, cultivationRepository.find(actorId).todayXpEarned());
      MvcResult repeatedClose =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions/{sessionId}:close", sessionId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(200, repeatedClose.getResponse().getStatus());
      assertEquals(
          closedBody.path("closedAt").asText(),
          objectMapper
              .readTree(repeatedClose.getResponse().getContentAsString())
              .path("closedAt")
              .asText());
      assertEquals(
          180,
          objectMapper
              .readTree(repeatedClose.getResponse().getContentAsString())
              .path("totalActiveSeconds")
              .asLong());
      assertEquals(
          1,
          objectMapper
              .readTree(repeatedClose.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());

      MvcResult heartbeatAfterClose =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              sessionId,
              "session-heartbeat-closed-0001",
              "{\"sequence\":5,\"active\":true}");
      assertEquals(410, heartbeatAfterClose.getResponse().getStatus());

      MvcResult expiringStart =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-expiring-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      UUID expiringSessionId =
          UUID.fromString(
              objectMapper
                  .readTree(expiringStart.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText());
      setCultivationReadingSessionAge(expiringSessionId, 90, 900);
      MvcResult replacementAfterIdle =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-after-idle-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      assertEquals(201, replacementAfterIdle.getResponse().getStatus());
      UUID replacementSessionId =
          UUID.fromString(
              objectMapper
                  .readTree(replacementAfterIdle.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText());
      MvcResult expiredHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              expiringSessionId,
              "session-heartbeat-expired-0001",
              "{\"sequence\":1,\"active\":true}");
      assertEquals(410, expiredHeartbeat.getResponse().getStatus());
      assertTrue(
          expiredHeartbeat.getResponse().getContentAsString().contains("READING_SESSION_EXPIRED"));
      assertEquals(0, sessionActiveSeconds(expiringSessionId));

      setCultivationReadingSessionAge(replacementSessionId, 60, 600);
      cultivationRepository.setEnabled(actorId, false);
      assertEquals(4, cultivationRepository.find(actorId).totalXp());
      assertEquals(4, cultivationRepository.find(actorId).todayXpEarned());
      MvcResult optedOutHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              replacementSessionId,
              "session-heartbeat-after-opt-out-0001",
              "{\"sequence\":1,\"active\":true}");
      assertEquals(410, optedOutHeartbeat.getResponse().getStatus());
      cultivationRepository.setEnabled(actorId, true);

      MvcResult accessRecheckStart =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", "session-start-access-recheck-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(startBody))
              .andReturn();
      UUID accessRecheckSessionId =
          UUID.fromString(
              objectMapper
                  .readTree(accessRecheckStart.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText());
      deleteReaderHistoryWork(actorId, readable.workId());
      MvcResult revokedHeartbeat =
          postCultivationHeartbeat(
              actorCookie,
              actorCsrf,
              accessRecheckSessionId,
              "session-heartbeat-revoked-0001",
              "{\"sequence\":1,\"active\":true}");
      assertEquals(410, revokedHeartbeat.getResponse().getStatus());
      assertTrue(
          revokedHeartbeat.getResponse().getContentAsString().contains("READING_SESSION_REVOKED"));
    } finally {
      deleteReaderHistoryFixtures(actorId, List.of(readable));
      deleteSessionTestUser(actorId);
      deleteReaderHistoryFixtures(otherUserId, List.of(foreign));
      deleteSessionTestUser(otherUserId);
      deleteSessionTestUser(policyBlockedId);
    }
  }

  @Test
  void cultivationReadingCrossesRankBoundaryAndCapsDailyAwardsAtOneHundredTwentyXp()
      throws Exception {
    UUID actorId = createSessionTestUser("Cultivation Daily XP Cap Owner");
    ReaderHistoryFixture readable =
        createReaderHistoryFixture(actorId, "Cultivation daily cap work", "DAILY_CAP_BODY");
    try {
      addCurrentPolicyAcceptances(actorId);
      cultivationRepository.setEnabled(actorId, true);
      SessionService.IssuedSession authSession = sessionService.create(actorId).orElseThrow();
      var cookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", authSession.cookieValue());
      String csrf = csrfTokenService.tokenFor(authSession.cookieValue());
      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/me/reading-sessions")
                      .cookie(cookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, csrf)
                      .header("Idempotency-Key", "daily-xp-cap-session-start-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(readingSessionStartBody(readable)))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      UUID sessionId =
          UUID.fromString(
              objectMapper
                  .readTree(created.getResponse().getContentAsString())
                  .path("sessionId")
                  .asText());
      setCultivationReadingSessionAge(sessionId, 5, 10_800);

      LocalDate activityDate;
      Instant ledgerActivityAt;
      try (Connection connection = dataSource.getConnection();
          PreparedStatement now = connection.prepareStatement("SELECT clock_timestamp()");
          ResultSet row = now.executeQuery()) {
        assertTrue(row.next());
        OffsetDateTime databaseNow = row.getObject(1, OffsetDateTime.class);
        ledgerActivityAt = databaseNow.toInstant().minusMillis(1);
        activityDate = ledgerActivityAt.atZone(ZoneOffset.UTC).toLocalDate();
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insertBucket =
              connection.prepareStatement(
                  """
                      INSERT INTO cultivation_daily_active_time
                          (user_id, activity_date, eligible_microseconds, catalog_version, policy_version)
                      VALUES (?, ?, 5999000000, 'cultivation-v1', 'xp-v1')
                      """);
          PreparedStatement insertXp =
              connection.prepareStatement(
                  """
                      INSERT INTO cultivation_xp_ledger
                          (user_id, source_session_id, active_minute_index, activity_at,
                           xp_delta, catalog_version, policy_version)
                      VALUES (?, ?, ?, ?, 1, 'cultivation-v1', 'xp-v1')
                      """)) {
        insertBucket.setObject(1, actorId);
        insertBucket.setObject(2, java.sql.Date.valueOf(activityDate));
        assertEquals(1, insertBucket.executeUpdate());
        for (int minuteIndex = 0; minuteIndex < 99; minuteIndex++) {
          insertXp.setObject(1, actorId);
          insertXp.setObject(2, sessionId);
          insertXp.setInt(3, minuteIndex);
          insertXp.setTimestamp(4, Timestamp.from(ledgerActivityAt));
          assertEquals(1, insertXp.executeUpdate());
        }
      }
      rebuildCultivationDailyXp(actorId, activityDate);
      setCultivationReadingSessionAge(sessionId, 5, 10_800);

      MvcResult reachesCap =
          postCultivationHeartbeat(
              cookie,
              csrf,
              sessionId,
              "daily-xp-cap-heartbeat-0001",
              "{\"sequence\":1,\"active\":true}");
      assertEquals(200, reachesCap.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(reachesCap.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());
      assertEquals(100, cultivationDailyXp(actorId, activityDate));
      assertEquals(100, cultivationRepository.find(actorId).totalXp());
      assertEquals("qi-1", cultivationService.get(actorId).currentRank().key());

      try (Connection connection = dataSource.getConnection();
          PreparedStatement fillDay =
              connection.prepareStatement(
                  "UPDATE cultivation_daily_active_time SET eligible_microseconds = 7199000000 "
                      + "WHERE user_id = ? AND activity_date = ?");
          PreparedStatement insertXp =
              connection.prepareStatement(
                  """
                      INSERT INTO cultivation_xp_ledger
                          (user_id, source_session_id, active_minute_index, activity_at,
                           xp_delta, catalog_version, policy_version)
                      VALUES (?, ?, ?, ?, 1, 'cultivation-v1', 'xp-v1')
                      """)) {
        fillDay.setObject(1, actorId);
        fillDay.setObject(2, java.sql.Date.valueOf(activityDate));
        assertEquals(1, fillDay.executeUpdate());
        for (int minuteIndex = 100; minuteIndex < 119; minuteIndex++) {
          insertXp.setObject(1, actorId);
          insertXp.setObject(2, sessionId);
          insertXp.setInt(3, minuteIndex);
          insertXp.setTimestamp(4, Timestamp.from(ledgerActivityAt));
          assertEquals(1, insertXp.executeUpdate());
        }
      }
      rebuildCultivationDailyXp(actorId, activityDate);

      setCultivationReadingSessionAge(sessionId, 30, 10_800);
      MvcResult afterCap =
          postCultivationHeartbeat(
              cookie,
              csrf,
              sessionId,
              "daily-xp-cap-heartbeat-0002",
              "{\"sequence\":2,\"active\":true}");
      assertEquals(200, afterCap.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(afterCap.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());
      assertEquals(120, cultivationDailyXp(actorId, activityDate));
      assertEquals(120, cultivationRepository.find(actorId).totalXp());

      setCultivationReadingSessionAge(sessionId, 30, 10_800);
      MvcResult afterCapHeartbeat =
          postCultivationHeartbeat(
              cookie,
              csrf,
              sessionId,
              "daily-xp-cap-heartbeat-0003",
              "{\"sequence\":3,\"active\":true}");
      assertEquals(200, afterCapHeartbeat.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(afterCapHeartbeat.getResponse().getContentAsString())
              .path("awardedXp")
              .asInt());
      assertEquals(120, cultivationRepository.find(actorId).todayXpEarned());
      assertEquals(120, cultivationDailyLedgerCount(actorId, activityDate));
    } finally {
      deleteReaderHistoryFixtures(actorId, List.of(readable));
      deleteSessionTestUser(actorId);
    }
  }

  @Test
  void importRightsStatementIsApprovedVersionedFailClosedAndAudited() throws Exception {
    UUID actorId = createSessionTestUser("Import Rights Reader");
    UUID blockedActorId = createSessionTestUser("Policy Blocked Import Reader");
    UUID unconfiguredActorId = createSessionTestUser("Unconfigured Import Creator");
    UUID approverId = createSessionTestUser("Import Rights Approver");
    var unconfiguredLibrary = libraryService.createLibrary(unconfiguredActorId, "No rights shelf");
    String statementVersion = "private-import-test-v1";
    String statementText = "# Synthetic import rights\n\nFor private test import only.";
    try {
      MvcResult unconfigured =
          mockMvc.perform(get("/api/v1/imports/rights-statement/current")).andReturn();
      assertEquals(503, unconfigured.getResponse().getStatus());
      assertTrue(
          unconfigured
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED"));
      addCurrentPolicyAcceptances(unconfiguredActorId);
      SessionService.IssuedSession unconfiguredSession =
          sessionService.create(unconfiguredActorId).orElseThrow();
      jakarta.servlet.http.Cookie unconfiguredCookie =
          new jakarta.servlet.http.Cookie(
              "__Host-novel_session", unconfiguredSession.cookieValue());
      MvcResult createWithoutStatement =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(unconfiguredCookie)
                      .header(
                          SessionCsrfTokenService.TOKEN_HEADER_NAME,
                          csrfTokenService.tokenFor(unconfiguredSession.cookieValue()))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              unconfiguredLibrary.id(),
                              UUID.randomUUID(),
                              statementVersion,
                              "story.txt",
                              "text/plain",
                              12,
                              true)))
              .andReturn();
      assertEquals(503, createWithoutStatement.getResponse().getStatus());
      assertTrue(
          createWithoutStatement
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED"));
      assertEquals(0, importJobCount());

      insertImportRightsStatement(statementVersion, statementText, approverId, "APPROVED", -1);
      insertImportRightsStatement("private-import-draft-v2", "Draft copy", null, "DRAFT", 3);
      MvcResult current =
          mockMvc.perform(get("/api/v1/imports/rights-statement/current")).andReturn();
      assertEquals(200, current.getResponse().getStatus());
      assertTrue(current.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode currentBody = objectMapper.readTree(current.getResponse().getContentAsString());
      assertEquals(statementVersion, currentBody.path("version").asText());
      assertEquals(statementText, currentBody.path("displayText").asText());

      SQLException immutable =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE import_rights_statements SET display_text = 'tampered' WHERE version = ?")) {
                  update.setString(1, statementVersion);
                  update.executeUpdate();
                }
              });
      assertEquals("55000", immutable.getSQLState());

      SessionService.IssuedSession blockedSession =
          sessionService.create(blockedActorId).orElseThrow();
      jakarta.servlet.http.Cookie blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());
      MvcResult missingConfirmation =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"statementVersion\":\""
                              + statementVersion
                              + "\",\"confirmed\":false}"))
              .andReturn();
      assertEquals(422, missingConfirmation.getResponse().getStatus());
      assertEquals(0, importRightsAcceptanceCount(blockedActorId));

      MvcResult policyBlocked =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"statementVersion\":\"" + statementVersion + "\",\"confirmed\":true}"))
              .andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      assertEquals(0, importRightsAcceptanceCount(blockedActorId));

      addCurrentPolicyAcceptances(actorId);
      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      String acceptanceKey = UUID.randomUUID().toString();

      MvcResult stale =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"statementVersion\":\"old-version\",\"confirmed\":true}"))
              .andReturn();
      assertEquals(409, stale.getResponse().getStatus());
      assertTrue(stale.getResponse().getContentAsString().contains(statementVersion));
      assertEquals(0, importRightsAcceptanceCount(actorId));

      String acceptedBody =
          "{\"statementVersion\":\"" + statementVersion + "\",\"confirmed\":true}";
      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", acceptanceKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody))
              .andReturn();
      assertEquals(200, accepted.getResponse().getStatus());
      JsonNode receipt = objectMapper.readTree(accepted.getResponse().getContentAsString());
      assertEquals(statementVersion, receipt.path("statementVersion").asText());
      assertEquals(
          HexFormat.of().formatHex(sha256(statementText)),
          receipt.path("statementSha256").asText());
      assertEquals(1, importRightsAcceptanceCount(actorId));
      SQLException immutableAudit =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection();
                    PreparedStatement update =
                        connection.prepareStatement(
                            "UPDATE import_rights_acceptances SET source = 'tampered' WHERE id = ?")) {
                  update.setObject(1, UUID.fromString(receipt.path("id").asText()));
                  update.executeUpdate();
                }
              });
      assertEquals("55000", immutableAudit.getSQLState());

      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", acceptanceKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody))
              .andReturn();
      JsonNode retryReceipt = objectMapper.readTree(retry.getResponse().getContentAsString());
      assertEquals(200, retry.getResponse().getStatus());
      assertEquals(receipt.path("id").asText(), retryReceipt.path("id").asText());
      assertEquals(receipt.path("acceptedAt").asText(), retryReceipt.path("acceptedAt").asText());
      assertEquals(1, importRightsAcceptanceCount(actorId));

      insertImportRightsStatement(
          "private-import-test-v2", "Second approved copy", approverId, "APPROVED", -1);
      MvcResult lateRetry =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", acceptanceKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(acceptedBody))
              .andReturn();
      JsonNode lateRetryReceipt =
          objectMapper.readTree(lateRetry.getResponse().getContentAsString());
      assertEquals(200, lateRetry.getResponse().getStatus());
      assertEquals(receipt.path("id").asText(), lateRetryReceipt.path("id").asText());

      MvcResult reusedKey =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", acceptanceKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"statementVersion\":\"private-import-test-v2\",\"confirmed\":true}"))
              .andReturn();
      assertEquals(409, reusedKey.getResponse().getStatus());
      assertTrue(reusedKey.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));
      assertEquals(1, importRightsAcceptanceCount(actorId));

      MvcResult secondAcceptance =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"statementVersion\":\"private-import-test-v2\",\"confirmed\":true}"))
              .andReturn();
      assertEquals(200, secondAcceptance.getResponse().getStatus());
      assertEquals(2, importRightsAcceptanceCount(actorId));
    } finally {
      deleteImportRightsTestAcceptances(actorId);
      deleteImportRightsTestAcceptances(blockedActorId);
      deleteLibrary(unconfiguredLibrary.id());
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(blockedActorId);
      deleteSessionTestUser(unconfiguredActorId);
      deleteImportRightsTestStatement(statementVersion);
      deleteImportRightsTestStatement("private-import-test-v2");
      deleteImportRightsTestStatement("private-import-draft-v2");
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void importCreationRequiresEveryGateAndIssuesOneActorBoundUploadTarget() throws Exception {
    UUID actorId = createSessionTestUser("Import Creation Owner");
    UUID otherActorId = createSessionTestUser("Import Creation Other Actor");
    UUID blockedActorId = createSessionTestUser("Import Creation Policy Blocked");
    UUID approverId = createSessionTestUser("Import Creation Approver");
    var library = libraryService.createLibrary(actorId, "Private import shelf");
    var otherLibrary = libraryService.createLibrary(otherActorId, "Other private shelf");
    var blockedLibrary = libraryService.createLibrary(blockedActorId, "Policy blocked shelf");
    String version = "private-import-create-v1";
    String statementText = "# Synthetic test rights\n\nTest-only private import statement.";
    byte[] content = "A short private test story.".getBytes(StandardCharsets.UTF_8);
    UUID rightsReceiptId = null;
    UUID importId = null;
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      UUID blockedReceiptId = UUID.randomUUID();
      insertImportRightsAcceptance(blockedActorId, blockedReceiptId, version, statementText);

      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherActorId).orElseThrow();
      SessionService.IssuedSession blockedSession =
          sessionService.create(blockedActorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      jakarta.servlet.http.Cookie blockedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", blockedSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      String otherCsrf = csrfTokenService.tokenFor(otherSession.cookieValue());
      String blockedCsrf = csrfTokenService.tokenFor(blockedSession.cookieValue());
      String filename = "C:\\fakepath\\private-test.txt";

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              UUID.randomUUID(),
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertEquals(0, importJobCount());

      MvcResult missingPolicy =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(blockedCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, blockedCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              blockedLibrary.id(),
                              blockedReceiptId,
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(403, missingPolicy.getResponse().getStatus());
      assertTrue(
          missingPolicy.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      assertTrue(importRightsAcceptanceConsumedAt(blockedReceiptId).isEmpty());
      assertEquals(0, importJobCount());

      String missingRights =
          importCreateJson(
              library.id(),
              UUID.randomUUID(),
              version,
              filename,
              "text/plain",
              content.length,
              null);
      MvcResult missing =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(missingRights))
              .andReturn();
      assertEquals(422, missing.getResponse().getStatus());
      assertTrue(
          missing
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_RIGHTS_CONFIRMATION_REQUIRED"));

      String falseRights =
          importCreateJson(
              library.id(),
              UUID.randomUUID(),
              version,
              filename,
              "text/plain",
              content.length,
              false);
      MvcResult unconfirmed =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(falseRights))
              .andReturn();
      assertEquals(422, unconfirmed.getResponse().getStatus());
      assertEquals(0, importJobCount(actorId));

      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"statementVersion\":\"" + version + "\",\"confirmed\":true}"))
              .andReturn();
      assertEquals(200, accepted.getResponse().getStatus());
      rightsReceiptId =
          UUID.fromString(
              objectMapper
                  .readTree(accepted.getResponse().getContentAsString())
                  .path("id")
                  .asText());

      MvcResult stale =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              "old-statement-v0",
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(409, stale.getResponse().getStatus());
      assertTrue(
          stale.getResponse().getContentAsString().contains("IMPORT_RIGHTS_STATEMENT_STALE"));

      MvcResult unsupportedType =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              "story.exe",
                              "application/octet-stream",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(422, unsupportedType.getResponse().getStatus());

      MvcResult mismatchedType =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              "story.txt",
                              "application/epub+zip",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(422, mismatchedType.getResponse().getStatus());

      MvcResult oversized =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              "story.txt",
                              "text/plain",
                              1025,
                              true)))
              .andReturn();
      assertEquals(413, oversized.getResponse().getStatus());

      MvcResult forgedFields =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true,
                              Map.of(
                                  "userId", otherActorId.toString(),
                                  "storageKey", "../../outside.upload",
                                  "createdAt", "2000-01-01T00:00:00Z"))))
              .andReturn();
      assertEquals(400, forgedFields.getResponse().getStatus());
      assertEquals(0, importJobCount());
      assertFalse(Files.exists(importStagingFileStore.targetPath(UUID.randomUUID())));

      MvcResult foreignLibrary =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              otherLibrary.id(),
                              rightsReceiptId,
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(404, foreignLibrary.getResponse().getStatus());
      assertEquals(0, importJobCount());

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(created.getResponse().getHeader("Location").startsWith("/api/v1/imports/"));
      JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
      importId = UUID.fromString(createdBody.path("job").path("id").asText());
      assertEquals("upload_pending", createdBody.path("job").path("status").asText());
      assertEquals("PUT", createdBody.path("uploadTarget").path("method").asText());
      assertEquals(
          "/api/v1/imports/" + importId + "/content",
          createdBody.path("uploadTarget").path("url").asText());
      String originalToken =
          createdBody.path("uploadTarget").path("headers").path("X-Import-Upload-Token").asText();
      assertFalse(originalToken.isBlank());
      assertEquals(1, importJobCount(actorId));
      assertEquals(actorId, importJobOwner(importId));
      assertEquals(library.id(), importJobLibrary(importId));
      assertEquals("private-test.txt", importJobFilename(importId));
      assertEquals("staging/" + importId + ".upload", importJobStorageKey(importId));
      assertTrue(
          Arrays.equals(
              sha256(originalToken.getBytes(StandardCharsets.US_ASCII)),
              importJobTokenHash(importId)));
      assertTrue(importRightsAcceptanceConsumedAt(rightsReceiptId).isPresent());

      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              rightsReceiptId,
                              version,
                              filename,
                              "text/plain",
                              content.length,
                              true)))
              .andReturn();
      assertEquals(201, retry.getResponse().getStatus());
      JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());
      assertEquals(importId.toString(), retryBody.path("job").path("id").asText());
      String rotatedToken =
          retryBody.path("uploadTarget").path("headers").path("X-Import-Upload-Token").asText();
      assertNotEquals(originalToken, rotatedToken);
      assertEquals(1, importJobCount(actorId));

      MvcResult wrongActor =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", importId)
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf)
                      .header("X-Import-Upload-Token", rotatedToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(content))
              .andReturn();
      assertEquals(404, wrongActor.getResponse().getStatus());
      MvcResult revokedToken =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", importId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", originalToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(content))
              .andReturn();
      assertEquals(404, revokedToken.getResponse().getStatus());
      assertFalse(Files.exists(importStagingFileStore.targetPath(importId)));

      byte[] invalidUtf8 = new byte[content.length];
      Arrays.fill(invalidUtf8, (byte) 'x');
      invalidUtf8[0] = (byte) 0xc3;
      invalidUtf8[1] = 0x28;
      MvcResult invalidContent =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", importId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", rotatedToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(invalidUtf8))
              .andReturn();
      assertEquals(422, invalidContent.getResponse().getStatus());
      assertTrue(
          invalidContent.getResponse().getContentAsString().contains("IMPORT_CONTENT_INVALID"));
      assertEquals("upload_pending", importJobStatus(importId));
      assertTrue(
          Arrays.equals(
              sha256(rotatedToken.getBytes(StandardCharsets.US_ASCII)),
              importJobTokenHash(importId)));
      assertFalse(Files.exists(importStagingFileStore.targetPath(importId)));

      MvcResult uploaded =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", importId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", rotatedToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(content))
              .andReturn();
      assertEquals(204, uploaded.getResponse().getStatus());
      assertTrue(uploaded.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(
          Arrays.equals(content, Files.readAllBytes(importStagingFileStore.targetPath(importId))));
      assertEquals("uploaded", importJobStatus(importId));
      assertTrue(Arrays.equals(sha256(content), importJobContentHash(importId)));
      assertEquals(content.length, importJobActualSize(importId));
      assertNull(importJobTokenHash(importId));

      MvcResult replayedUpload =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", importId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", rotatedToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(content))
              .andReturn();
      assertEquals(404, replayedUpload.getResponse().getStatus());

      MvcResult secondAcceptance =
          mockMvc
              .perform(
                  post("/api/v1/imports/rights-statement/acceptance")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"statementVersion\":\"" + version + "\",\"confirmed\":true}"))
              .andReturn();
      UUID secondReceipt =
          UUID.fromString(
              objectMapper
                  .readTree(secondAcceptance.getResponse().getContentAsString())
                  .path("id")
                  .asText());
      MvcResult secondCreated =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              secondReceipt,
                              version,
                              "oversized.txt",
                              "text/plain",
                              10,
                              true)))
              .andReturn();
      assertEquals(201, secondCreated.getResponse().getStatus());
      UUID oversizedJobId =
          UUID.fromString(
              objectMapper
                  .readTree(secondCreated.getResponse().getContentAsString())
                  .path("job")
                  .path("id")
                  .asText());
      String oversizedToken =
          objectMapper
              .readTree(secondCreated.getResponse().getContentAsString())
              .path("uploadTarget")
              .path("headers")
              .path("X-Import-Upload-Token")
              .asText();
      MvcResult overrun =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", oversizedJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", oversizedToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(new byte[1025]))
              .andReturn();
      assertEquals(413, overrun.getResponse().getStatus());
      assertFalse(Files.exists(importStagingFileStore.targetPath(oversizedJobId)));
      assertEquals("upload_pending", importJobStatus(oversizedJobId));
    } finally {
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteImportJobs(blockedActorId);
      deleteImportRightsTestAcceptances(blockedActorId);
      deleteLibrary(library.id());
      deleteLibrary(otherLibrary.id());
      deleteLibrary(blockedLibrary.id());
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(otherActorId);
      deleteSessionTestUser(blockedActorId);
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void privateEpubParseQueueIsOwnerScopedIdempotentAndStoresOnlyCompletePreviews()
      throws Exception {
    UUID actorId = createSessionTestUser("EPUB Parse Owner");
    UUID otherActorId = createSessionTestUser("EPUB Parse Other Owner");
    UUID approverId = createSessionTestUser("EPUB Parse Approver");
    var library = libraryService.createLibrary(actorId, "Private parser shelf");
    String version = "private-import-parse-integration-v1";
    String statementText = "Synthetic rights copy for private EPUB parser integration coverage.";
    UUID validJobId = UUID.randomUUID();
    UUID invalidJobId = UUID.randomUUID();
    UUID validReceipt = UUID.randomUUID();
    UUID invalidReceipt = UUID.randomUUID();
    var actorSession = sessionService.create(actorId).orElseThrow();
    var otherSession = sessionService.create(otherActorId).orElseThrow();
    jakarta.servlet.http.Cookie actorCookie =
        new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
    jakarta.servlet.http.Cookie otherCookie =
        new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
    String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
    String otherCsrf = csrfTokenService.tokenFor(otherSession.cookieValue());

    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      String longTitle = "📖".repeat(120);
      byte[] validEpub =
          tinyEpub(
              longTitle,
              "Chapter One",
              "A short private preview.",
              "Chapter One",
              "A short private preview.");
      byte[] malformedEpub = tinyEpub("A Broken Novel", "broken", "<opf-malformed");
      assertTrue(
          validEpub.length <= 1024,
          "integration EPUB fixture must fit the configured upload cap: " + validEpub.length);
      assertTrue(
          malformedEpub.length <= 1024,
          "malformed integration EPUB must fit the configured upload cap");
      insertUploadedEpubImportFixture(
          validJobId, actorId, library.id(), validReceipt, version, statementText, validEpub, 0);
      insertUploadedEpubImportFixture(
          invalidJobId,
          actorId,
          library.id(),
          invalidReceipt,
          version,
          statementText,
          malformedEpub,
          0);
      importStagingFileStore.store(
          validJobId, new ByteArrayInputStream(validEpub), validEpub.length);
      importStagingFileStore.store(
          invalidJobId, new ByteArrayInputStream(malformedEpub), malformedEpub.length);

      assertEquals(
          404,
          mockMvc
              .perform(get("/api/v1/imports/{id}", validJobId).cookie(otherCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          404,
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", validJobId)
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf))
              .andReturn()
              .getResponse()
              .getStatus());
      MvcResult missingCsrf =
          mockMvc
              .perform(post("/api/v1/imports/{id}/parse", validJobId).cookie(actorCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertEquals(0, importParseJobCount(validJobId));

      int workCountBefore = workCountForLibrary(library.id());
      int chapterCountBefore = chapterCountForLibrary(library.id());
      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, accepted.getResponse().getStatus());
      assertTrue(accepted.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("2", accepted.getResponse().getHeader("Retry-After"));
      assertEquals("/api/v1/imports/" + validJobId, accepted.getResponse().getHeader("Location"));
      assertEquals(1, importParseJobCount(validJobId));

      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, retry.getResponse().getStatus());
      assertEquals(
          1, importParseJobCount(validJobId), "repeated parse calls must reuse the parser job");

      JsonNode readyStatus = awaitImportStatus(actorCookie, validJobId, "ready_to_commit");
      assertEquals("📖".repeat(120), readyStatus.path("previewSummary").path("title").asText());
      assertEquals("An Author", readyStatus.path("previewSummary").path("author").asText());
      assertEquals(2, readyStatus.path("previewSummary").path("chapterCount").asInt());
      assertEquals("ready_to_commit", importParseJobStatus(validJobId));
      assertTrue(importParsePreviewByteCount(validJobId) > 0);
      assertTrue(importParsePreviewByteCount(validJobId) <= 1_048_576);
      assertEquals(32, importParseEtagBytes(validJobId));
      assertEquals("epub3-v2", importParseParserVersion(validJobId));
      JsonNode storedParseResult = objectMapper.readTree(importParsePreviewJson(validJobId));
      assertEquals("epub3-v2", storedParseResult.path("parserVersion").asText());
      assertEquals(2, storedParseResult.path("chapters").get(0).path("segments").size());
      assertEquals(
          "h1",
          storedParseResult.path("chapters").get(0).path("segments").get(0).path("kind").asText());
      assertEquals(
          "A short private preview.",
          storedParseResult.path("chapters").get(0).path("segments").get(1).path("text").asText());
      assertEquals(workCountBefore, workCountForLibrary(library.id()));
      assertEquals(chapterCountBefore, chapterCountForLibrary(library.id()));

      MvcResult foreignPreview =
          mockMvc
              .perform(get("/api/v1/imports/{id}/preview", validJobId).cookie(otherCookie))
              .andReturn();
      assertEquals(404, foreignPreview.getResponse().getStatus());
      MvcResult firstPageResult =
          mockMvc
              .perform(
                  get("/api/v1/imports/{id}/preview", validJobId)
                      .queryParam("limit", "1")
                      .cookie(actorCookie))
              .andReturn();
      assertEquals(200, firstPageResult.getResponse().getStatus());
      assertTrue(firstPageResult.getResponse().getHeader("Cache-Control").contains("no-store"));
      String originalEtag = firstPageResult.getResponse().getHeader("ETag");
      JsonNode firstPage =
          objectMapper.readTree(firstPageResult.getResponse().getContentAsString());
      assertEquals(1, firstPage.path("version").asInt());
      assertEquals(2, firstPage.path("chapterCount").asInt());
      assertEquals(1, firstPage.path("chapters").size());
      assertTrue(firstPage.path("nextCursor").isTextual());
      assertFalse(firstPageResult.getResponse().getContentAsString().contains("sourcePath"));
      assertFalse(firstPageResult.getResponse().getContentAsString().contains("textPreview"));
      assertFalse(firstPageResult.getResponse().getContentAsString().contains("segments"));
      String firstChapterId = firstPage.path("chapters").get(0).path("id").asText();

      String originalCursor = firstPage.path("nextCursor").asText();
      MvcResult secondPageResult =
          mockMvc
              .perform(
                  get("/api/v1/imports/{id}/preview", validJobId)
                      .queryParam("limit", "1")
                      .queryParam("cursor", originalCursor)
                      .cookie(actorCookie))
              .andReturn();
      assertEquals(200, secondPageResult.getResponse().getStatus());
      assertEquals(originalEtag, secondPageResult.getResponse().getHeader("ETag"));
      JsonNode secondPage =
          objectMapper.readTree(secondPageResult.getResponse().getContentAsString());
      assertEquals(1, secondPage.path("chapters").size());
      String secondChapterId = secondPage.path("chapters").get(0).path("id").asText();
      assertNotEquals(firstChapterId, secondChapterId);

      byte[] invalidOrder =
          objectMapper.writeValueAsBytes(Map.of("chapterOrder", List.of(firstChapterId)));
      MvcResult invalidMapping =
          mockMvc
              .perform(
                  patch("/api/v1/imports/{id}/preview", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", originalEtag)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(invalidOrder))
              .andReturn();
      assertEquals(422, invalidMapping.getResponse().getStatus());
      assertEquals(
          originalEtag,
          mockMvc
              .perform(
                  get("/api/v1/imports/{id}/preview", validJobId)
                      .queryParam("limit", "1")
                      .cookie(actorCookie))
              .andReturn()
              .getResponse()
              .getHeader("ETag"));

      MvcResult missingPatchCsrf =
          mockMvc
              .perform(
                  patch("/api/v1/imports/{id}/preview", validJobId)
                      .cookie(actorCookie)
                      .header("If-Match", originalEtag)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Edited Book\"}"))
              .andReturn();
      assertEquals(403, missingPatchCsrf.getResponse().getStatus());
      MvcResult foreignPatch =
          mockMvc
              .perform(
                  patch("/api/v1/imports/{id}/preview", validJobId)
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf)
                      .header("If-Match", originalEtag)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Other Owner\"}"))
              .andReturn();
      assertEquals(404, foreignPatch.getResponse().getStatus());

      byte[] validEdit =
          objectMapper.writeValueAsBytes(
              Map.of(
                  "title", "Edited Book",
                  "author", "Edited Author",
                  "language", "en",
                  "chapterOrder", List.of(secondChapterId, firstChapterId)));
      MvcResult updatedPreviewResult =
          mockMvc
              .perform(
                  patch("/api/v1/imports/{id}/preview", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", originalEtag)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(validEdit))
              .andReturn();
      assertEquals(200, updatedPreviewResult.getResponse().getStatus());
      String updatedEtag = updatedPreviewResult.getResponse().getHeader("ETag");
      assertNotEquals(originalEtag, updatedEtag);
      JsonNode updatedPreview =
          objectMapper.readTree(updatedPreviewResult.getResponse().getContentAsString());
      assertEquals(2, updatedPreview.path("version").asInt());
      assertEquals("Edited Book", updatedPreview.path("title").asText());
      assertEquals("Edited Author", updatedPreview.path("author").asText());
      assertEquals("en", updatedPreview.path("language").asText());
      assertEquals(secondChapterId, updatedPreview.path("chapters").get(0).path("id").asText());
      assertEquals(1, updatedPreview.path("chapters").get(0).path("ordinal").asInt());
      assertEquals(1, importParseAttempt(validJobId));
      assertEquals(2, importPreviewVersion(validJobId));
      assertEquals(workCountBefore, workCountForLibrary(library.id()));
      assertEquals(chapterCountBefore, chapterCountForLibrary(library.id()));

      MvcResult staleEdit =
          mockMvc
              .perform(
                  patch("/api/v1/imports/{id}/preview", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", originalEtag)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Stale Edit\"}"))
              .andReturn();
      assertEquals(412, staleEdit.getResponse().getStatus());
      MvcResult staleCursor =
          mockMvc
              .perform(
                  get("/api/v1/imports/{id}/preview", validJobId)
                      .queryParam("limit", "1")
                      .queryParam("cursor", originalCursor)
                      .cookie(actorCookie))
              .andReturn();
      assertEquals(400, staleCursor.getResponse().getStatus());
      JsonNode editedStatus =
          objectMapper.readTree(
              mockMvc
                  .perform(get("/api/v1/imports/{id}", validJobId).cookie(actorCookie))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertEquals("Edited Book", editedStatus.path("previewSummary").path("title").asText());

      addCurrentPolicyAcceptances(actorId);
      String commitKey = "import-commit-idempotency-0001";
      MvcResult missingCommitEtag =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("Idempotency-Key", commitKey))
              .andReturn();
      assertEquals(428, missingCommitEtag.getResponse().getStatus());
      MvcResult missingCommitCsrf =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", validJobId)
                      .cookie(actorCookie)
                      .header("If-Match", updatedEtag)
                      .header("Idempotency-Key", commitKey))
              .andReturn();
      assertEquals(403, missingCommitCsrf.getResponse().getStatus());
      MvcResult foreignCommit =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", validJobId)
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf)
                      .header("If-Match", updatedEtag)
                      .header("Idempotency-Key", commitKey))
              .andReturn();
      assertEquals(404, foreignCommit.getResponse().getStatus());
      MvcResult staleCommit =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", originalEtag)
                      .header("Idempotency-Key", commitKey))
              .andReturn();
      assertEquals(412, staleCommit.getResponse().getStatus());
      MvcResult missingCommitKey =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", validJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", updatedEtag))
              .andReturn();
      assertEquals(400, missingCommitKey.getResponse().getStatus());

      createFailImportCommitSegmentsTrigger();
      try {
        MvcResult rollbackCommit =
            mockMvc
                .perform(
                    post("/api/v1/imports/{id}/commit", validJobId)
                        .cookie(actorCookie)
                        .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                        .header("If-Match", updatedEtag)
                        .header("Idempotency-Key", commitKey))
                .andReturn();
        assertEquals(503, rollbackCommit.getResponse().getStatus());
        assertEquals("uploaded", importJobStatus(validJobId));
        assertEquals("ready_to_commit", importParseJobStatus(validJobId));
        assertTrue(importParsePreviewByteCount(validJobId) > 0);
        assertEquals(
            workCountBefore,
            workCountForLibrary(library.id()),
            "a failed chapter/segment write must roll back the work row");
        assertEquals(
            chapterCountBefore,
            chapterCountForLibrary(library.id()),
            "a failed chapter/segment write must roll back all chapter rows");
      } finally {
        dropFailImportCommitSegmentsTrigger();
      }

      Path interruptedStagingCleanup = importStagingFileStore.targetPath(validJobId);
      Path cleanupBlocker = interruptedStagingCleanup.resolve("interrupted-cleanup.bin");
      Files.deleteIfExists(interruptedStagingCleanup);
      Files.createDirectory(interruptedStagingCleanup);
      Files.writeString(cleanupBlocker, "synthetic interrupted cleanup");
      try {
        CountDownLatch commitReady = new CountDownLatch(2);
        CountDownLatch commitStart = new CountDownLatch(1);
        ExecutorService commitExecutor = Executors.newFixedThreadPool(2);
        try {
          List<Future<MvcResult>> commitRequests = new ArrayList<>();
          for (int index = 0; index < 2; index++) {
            commitRequests.add(
                commitExecutor.submit(
                    () -> {
                      commitReady.countDown();
                      if (!commitStart.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent import commit did not start");
                      }
                      return mockMvc
                          .perform(
                              post("/api/v1/imports/{id}/commit", validJobId)
                                  .cookie(actorCookie)
                                  .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                                  .header("If-Match", updatedEtag)
                                  .header("Idempotency-Key", commitKey))
                          .andReturn();
                    }));
          }
          assertTrue(
              commitReady.await(10, TimeUnit.SECONDS),
              "concurrent commit requests did not become ready");
          commitStart.countDown();
          MvcResult committed = commitRequests.get(0).get(20, TimeUnit.SECONDS);
          MvcResult concurrentRetry = commitRequests.get(1).get(20, TimeUnit.SECONDS);
          assertEquals(201, committed.getResponse().getStatus());
          assertEquals(201, concurrentRetry.getResponse().getStatus());
          assertTrue(committed.getResponse().getHeader("Cache-Control").contains("no-store"));
          assertEquals(
              "/api/v1/works/"
                  + objectMapper
                      .readTree(committed.getResponse().getContentAsString())
                      .path("id")
                      .asText(),
              committed.getResponse().getHeader("Location"));
          JsonNode committedWork =
              objectMapper.readTree(committed.getResponse().getContentAsString());
          JsonNode concurrentWork =
              objectMapper.readTree(concurrentRetry.getResponse().getContentAsString());
          assertEquals(committedWork.path("id").asText(), concurrentWork.path("id").asText());
          assertEquals("Edited Book", committedWork.path("title").asText());
          assertEquals("Edited Author", committedWork.path("author").asText());
          assertEquals("en", committedWork.path("language").asText());
          assertEquals("private", committedWork.path("visibility").asText());
          assertEquals(2, committedWork.path("chapterCount").asInt());
          UUID committedWorkId = UUID.fromString(committedWork.path("id").asText());
          assertEquals(1, workCountForLibrary(library.id()) - workCountBefore);
          assertEquals(2, chapterCountForLibrary(library.id()) - chapterCountBefore);
          assertEquals(2, revisionCountForWork(committedWorkId));
          assertEquals(4, segmentCountForWork(committedWorkId));
          assertTrue(revisionContentForWork(committedWorkId).contains("A short private preview."));
          assertEquals("committed", importJobStatus(validJobId));
          assertEquals("committed", importParseJobStatus(validJobId));
          JsonNode committedStatus =
              objectMapper.readTree(
                  mockMvc
                      .perform(get("/api/v1/imports/{id}", validJobId).cookie(actorCookie))
                      .andReturn()
                      .getResponse()
                      .getContentAsString());
          assertEquals("committed", committedStatus.path("status").asText());
          assertFalse(committedStatus.has("previewSummary"));
          assertEquals(
              0,
              importParsePreviewByteCount(validJobId),
              "committing must clear private preview and source-segment staging JSON");
          assertEquals(0, importParseEtagBytes(validJobId));
          assertEquals(
              409,
              mockMvc
                  .perform(get("/api/v1/imports/{id}/preview", validJobId).cookie(actorCookie))
                  .andReturn()
                  .getResponse()
                  .getStatus());
          assertNull(
              importJobStagingFileDeletedAt(validJobId),
              "the commit must survive a process interruption before staging cleanup");
          assertTrue(Files.exists(cleanupBlocker));
          assertFalse(
              importParseService.processNext(),
              "a committed job must not be reclaimed by the parser worker");

          MvcResult retryCommit =
              mockMvc
                  .perform(
                      post("/api/v1/imports/{id}/commit", validJobId)
                          .cookie(actorCookie)
                          .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                          .header("If-Match", updatedEtag)
                          .header("Idempotency-Key", commitKey))
                  .andReturn();
          assertEquals(201, retryCommit.getResponse().getStatus());
          assertEquals(
              committedWork.path("id").asText(),
              objectMapper
                  .readTree(retryCommit.getResponse().getContentAsString())
                  .path("id")
                  .asText());
          MvcResult reusedKey =
              mockMvc
                  .perform(
                      post("/api/v1/imports/{id}/commit", validJobId)
                          .cookie(actorCookie)
                          .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                          .header("If-Match", originalEtag)
                          .header("Idempotency-Key", commitKey))
                  .andReturn();
          assertEquals(409, reusedKey.getResponse().getStatus());
          MvcResult keyReusedForOtherImport =
              mockMvc
                  .perform(
                      post("/api/v1/imports/{id}/commit", invalidJobId)
                          .cookie(actorCookie)
                          .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                          .header("If-Match", updatedEtag)
                          .header("Idempotency-Key", commitKey))
                  .andReturn();
          assertEquals(409, keyReusedForOtherImport.getResponse().getStatus());
          MvcResult otherKey =
              mockMvc
                  .perform(
                      post("/api/v1/imports/{id}/commit", validJobId)
                          .cookie(actorCookie)
                          .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                          .header("If-Match", updatedEtag)
                          .header("Idempotency-Key", "import-commit-idempotency-0002"))
                  .andReturn();
          assertEquals(409, otherKey.getResponse().getStatus());
          assertEquals(
              1,
              workCountForLibrary(library.id()) - workCountBefore,
              "same-key retries and concurrent requests must create exactly one work");
        } finally {
          commitExecutor.shutdownNow();
        }

        assertThrows(
            IllegalStateException.class,
            importStagingCleanupService::cleanupExpiredStaging,
            "a failed post-commit file cleanup must leave the committed job retryable");
        assertNull(importJobStagingFileDeletedAt(validJobId));
        Files.delete(cleanupBlocker);
        Files.delete(interruptedStagingCleanup);
        assertEquals(1, importStagingCleanupService.cleanupExpiredStaging());
        assertNotNull(importJobStagingFileDeletedAt(validJobId));
        assertFalse(Files.exists(interruptedStagingCleanup));
      } finally {
        if (Files.exists(cleanupBlocker)) Files.delete(cleanupBlocker);
        Files.deleteIfExists(interruptedStagingCleanup);
      }

      MvcResult failedRequest =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", invalidJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, failedRequest.getResponse().getStatus());
      JsonNode failedStatus = awaitImportStatus(actorCookie, invalidJobId, "failed");
      assertEquals("EPUB_XML_INVALID", failedStatus.path("failureCode").asText());
      assertTrue(failedStatus.path("warnings").size() > 0);
      assertEquals(0, importParsePreviewByteCount(invalidJobId));
      assertEquals(workCountBefore + 1, workCountForLibrary(library.id()));
      assertEquals(chapterCountBefore + 2, chapterCountForLibrary(library.id()));
    } finally {
      importStagingFileStore.delete(validJobId);
      importStagingFileStore.delete(invalidJobId);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteImportedWorksForLibrary(library.id());
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(otherActorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void privateTxtImportParsesReviewsAndCommitsAsPlainText() throws Exception {
    UUID actorId = createSessionTestUser("Plain-text import owner");
    UUID approverId = createSessionTestUser("Plain-text import approver");
    var library = libraryService.createLibrary(actorId, "Private TXT shelf");
    String version = "private-import-txt-integration-v1";
    String statementText = "Synthetic rights copy for private TXT parser integration coverage.";
    UUID jobId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    var actorSession = sessionService.create(actorId).orElseThrow();
    jakarta.servlet.http.Cookie actorCookie =
        new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
    String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
    byte[] sourceText =
        "First paragraph.\r\n\r\nSecond paragraph with Vietnamese text: câu chuyện riêng tư.\n"
            .getBytes(StandardCharsets.UTF_8);

    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      insertUploadedImportFixture(
          jobId,
          actorId,
          library.id(),
          receiptId,
          version,
          statementText,
          "Private Text Novel.txt",
          "text/plain",
          sourceText);
      importStagingFileStore.store(jobId, new ByteArrayInputStream(sourceText), sourceText.length);

      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, accepted.getResponse().getStatus());

      JsonNode readyStatus = awaitImportStatus(actorCookie, jobId, "ready_to_commit");
      assertEquals("Private Text Novel", readyStatus.path("previewSummary").path("title").asText());
      assertEquals(1, readyStatus.path("previewSummary").path("chapterCount").asInt());
      assertEquals("txt-v1", importParseParserVersion(jobId));
      JsonNode stagedPreview = objectMapper.readTree(importParsePreviewJson(jobId));
      assertEquals("txt-v1", stagedPreview.path("parserVersion").asText());
      assertEquals("und", stagedPreview.path("metadata").path("language").asText());
      assertEquals(2, stagedPreview.path("chapters").get(0).path("segments").size());
      assertTrue(
          stagedPreview
              .path("chapters")
              .get(0)
              .path("segments")
              .get(1)
              .path("text")
              .asText()
              .contains("câu chuyện riêng tư"));
      assertEquals(
          "TXT_SINGLE_CHAPTER", stagedPreview.path("warnings").get(0).path("code").asText());

      MvcResult preview =
          mockMvc
              .perform(get("/api/v1/imports/{id}/preview", jobId).cookie(actorCookie))
              .andReturn();
      assertEquals(200, preview.getResponse().getStatus());
      String etag = preview.getResponse().getHeader("ETag");
      MvcResult committed =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", etag)
                      .header("Idempotency-Key", "plain-text-import-commit-0001"))
              .andReturn();
      assertEquals(201, committed.getResponse().getStatus());
      JsonNode work = objectMapper.readTree(committed.getResponse().getContentAsString());
      assertEquals("Private Text Novel", work.path("title").asText());
      assertEquals("und", work.path("language").asText());
      assertEquals(1, work.path("chapterCount").asInt());
      UUID workId = UUID.fromString(work.path("id").asText());
      assertEquals(1, revisionCountForWork(workId));
      assertEquals(2, segmentCountForWork(workId));
      assertEquals("plain_text", revisionSourceFormatForWork(workId));
      assertTrue(revisionContentForWork(workId).contains("Second paragraph with Vietnamese text"));
      assertEquals("committed", importJobStatus(jobId));
      assertEquals("committed", importParseJobStatus(jobId));
    } finally {
      importStagingFileStore.delete(jobId);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteImportedWorksForLibrary(library.id());
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void privateHtmlImportParsesReviewsAndCommitsAsXhtml() throws Exception {
    UUID actorId = createSessionTestUser("HTML import owner");
    UUID approverId = createSessionTestUser("HTML import approver");
    var library = libraryService.createLibrary(actorId, "Private HTML shelf");
    String version = "private-import-html-integration-v1";
    String statementText = "Synthetic rights copy for private HTML parser integration coverage.";
    UUID jobId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    var actorSession = sessionService.create(actorId).orElseThrow();
    jakarta.servlet.http.Cookie actorCookie =
        new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
    String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
    byte[] sourceHtml =
        ("<!doctype html><html lang='vi'><head><title>Thành phố mưa</title>"
                + "<meta name='author' content='Minh An'></head><body><nav>Không nhập điều hướng.</nav>"
                + "<article><h1>Mở đầu</h1><p>Câu chuyện riêng tư.</p>"
                + "<blockquote><p>Gió qua rừng.</p></blockquote>"
                + "<script>window.secret='không lưu mã script'</script></article></body></html>")
            .getBytes(StandardCharsets.UTF_8);

    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      insertUploadedImportFixture(
          jobId,
          actorId,
          library.id(),
          receiptId,
          version,
          statementText,
          "Private HTML Novel.html",
          "text/html",
          sourceHtml);
      importStagingFileStore.store(jobId, new ByteArrayInputStream(sourceHtml), sourceHtml.length);

      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, accepted.getResponse().getStatus());

      JsonNode readyStatus = awaitImportStatus(actorCookie, jobId, "ready_to_commit");
      assertEquals("Thành phố mưa", readyStatus.path("previewSummary").path("title").asText());
      assertEquals(1, readyStatus.path("previewSummary").path("chapterCount").asInt());
      assertEquals("html-v1", importParseParserVersion(jobId));
      JsonNode stagedPreview = objectMapper.readTree(importParsePreviewJson(jobId));
      assertEquals("vi", stagedPreview.path("metadata").path("language").asText());
      assertEquals("Minh An", stagedPreview.path("metadata").path("creators").get(0).asText());
      assertEquals("Mở đầu", stagedPreview.path("chapters").get(0).path("title").asText());
      assertEquals(3, stagedPreview.path("chapters").get(0).path("segments").size());
      assertEquals(
          "heading",
          stagedPreview.path("chapters").get(0).path("segments").get(0).path("kind").asText());
      assertEquals(
          "quote",
          stagedPreview.path("chapters").get(0).path("segments").get(2).path("kind").asText());
      assertEquals(
          "HTML_SINGLE_DOCUMENT", stagedPreview.path("warnings").get(0).path("code").asText());
      assertFalse(importParsePreviewJson(jobId).contains("không lưu mã script"));

      MvcResult preview =
          mockMvc
              .perform(get("/api/v1/imports/{id}/preview", jobId).cookie(actorCookie))
              .andReturn();
      assertEquals(200, preview.getResponse().getStatus());
      String etag = preview.getResponse().getHeader("ETag");
      MvcResult committed =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", etag)
                      .header("Idempotency-Key", "html-import-commit-0001"))
              .andReturn();
      assertEquals(201, committed.getResponse().getStatus());
      JsonNode work = objectMapper.readTree(committed.getResponse().getContentAsString());
      assertEquals("Thành phố mưa", work.path("title").asText());
      assertEquals(1, work.path("chapterCount").asInt());
      UUID workId = UUID.fromString(work.path("id").asText());
      assertEquals(1, revisionCountForWork(workId));
      assertEquals(3, segmentCountForWork(workId));
      assertEquals("xhtml", revisionSourceFormatForWork(workId));
      assertTrue(revisionContentForWork(workId).contains("Câu chuyện riêng tư."));
      assertFalse(revisionContentForWork(workId).contains("window.secret"));
      assertEquals("html-c0-s1", firstSegmentAnchorForWork(workId));
      assertEquals("committed", importJobStatus(jobId));
      assertEquals("committed", importParseJobStatus(jobId));
    } finally {
      importStagingFileStore.delete(jobId);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteImportedWorksForLibrary(library.id());
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void privateMarkdownImportSplitsHeadingsAndCommitsSafeTextAsMarkdown() throws Exception {
    UUID actorId = createSessionTestUser("Markdown import owner");
    UUID approverId = createSessionTestUser("Markdown import approver");
    var library = libraryService.createLibrary(actorId, "Private Markdown shelf");
    String version = "private-import-markdown-integration-v1";
    String statementText =
        "Synthetic rights copy for private Markdown parser integration coverage.";
    UUID jobId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    var actorSession = sessionService.create(actorId).orElseThrow();
    jakarta.servlet.http.Cookie actorCookie =
        new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
    String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
    byte[] sourceMarkdown =
        ("# Thành phố mưa\n\nLời dẫn riêng tư.\n\n## Chương một\n\n"
                + "Có [nhãn liên kết](https://private.example/secret), "
                + "![mô tả bìa](https://private.example/cover.png) và `mã`.\n\n"
                + "<script>window.secret='không lưu script'</script>\n\n"
                + "## Chương hai\n\nMưa trở lại.")
            .getBytes(StandardCharsets.UTF_8);

    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      insertUploadedImportFixture(
          jobId,
          actorId,
          library.id(),
          receiptId,
          version,
          statementText,
          "Private Markdown Novel.md",
          "text/markdown",
          sourceMarkdown);
      importStagingFileStore.store(
          jobId, new ByteArrayInputStream(sourceMarkdown), sourceMarkdown.length);

      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(202, accepted.getResponse().getStatus());

      JsonNode readyStatus = awaitImportStatus(actorCookie, jobId, "ready_to_commit");
      assertEquals("Thành phố mưa", readyStatus.path("previewSummary").path("title").asText());
      assertEquals(3, readyStatus.path("previewSummary").path("chapterCount").asInt());
      assertEquals("markdown-v1", importParseParserVersion(jobId));
      JsonNode stagedPreview = objectMapper.readTree(importParsePreviewJson(jobId));
      assertEquals("Mở đầu", stagedPreview.path("chapters").get(0).path("title").asText());
      assertEquals("Chương một", stagedPreview.path("chapters").get(1).path("title").asText());
      assertEquals("Chương hai", stagedPreview.path("chapters").get(2).path("title").asText());
      assertEquals(
          "heading",
          stagedPreview.path("chapters").get(1).path("segments").get(0).path("kind").asText());
      assertTrue(importParsePreviewJson(jobId).contains("nhãn liên kết"));
      assertTrue(importParsePreviewJson(jobId).contains("mô tả bìa"));
      assertFalse(importParsePreviewJson(jobId).contains("private.example"));
      assertFalse(importParsePreviewJson(jobId).contains("không lưu script"));
      assertEquals(
          "MARKDOWN_FORMATTING_NORMALIZED",
          stagedPreview.path("warnings").get(0).path("code").asText());
      assertEquals(
          "MARKDOWN_CHAPTERS_FROM_HEADINGS",
          stagedPreview.path("warnings").get(1).path("code").asText());

      MvcResult preview =
          mockMvc
              .perform(get("/api/v1/imports/{id}/preview", jobId).cookie(actorCookie))
              .andReturn();
      assertEquals(200, preview.getResponse().getStatus());
      String etag = preview.getResponse().getHeader("ETag");
      MvcResult committed =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/commit", jobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("If-Match", etag)
                      .header("Idempotency-Key", "markdown-import-commit-0001"))
              .andReturn();
      assertEquals(201, committed.getResponse().getStatus());
      JsonNode work = objectMapper.readTree(committed.getResponse().getContentAsString());
      assertEquals("Thành phố mưa", work.path("title").asText());
      assertEquals(3, work.path("chapterCount").asInt());
      UUID workId = UUID.fromString(work.path("id").asText());
      assertEquals(3, revisionCountForWork(workId));
      assertEquals(5, segmentCountForWork(workId));
      assertEquals("markdown", revisionSourceFormatForWork(workId));
      assertTrue(revisionContentForWork(workId).contains("nhãn liên kết"));
      assertTrue(revisionContentForWork(workId).contains("mô tả bìa"));
      assertFalse(revisionContentForWork(workId).contains("private.example"));
      assertFalse(revisionContentForWork(workId).contains("window.secret"));
      assertEquals("md-c0-s1", firstSegmentAnchorForWork(workId));
      assertEquals("committed", importJobStatus(jobId));
      assertEquals("committed", importParseJobStatus(jobId));
    } finally {
      importStagingFileStore.delete(jobId);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteImportedWorksForLibrary(library.id());
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void expiredImportStagingIsDeletedAndFailedCleanupCanRetry() throws Exception {
    UUID actorId = createSessionTestUser("Import Retention Owner");
    UUID approverId = createSessionTestUser("Import Retention Approver");
    var library = libraryService.createLibrary(actorId, "Retention test shelf");
    String version = "private-import-retention-v1";
    String statementText = "Synthetic test-only import retention statement.";
    UUID blockedJobId = UUID.randomUUID();
    UUID expiredUploadedId = UUID.randomUUID();
    UUID expiredPendingId = UUID.randomUUID();
    UUID activeUploadedId = UUID.randomUUID();
    Path blockedTarget = importStagingFileStore.targetPath(blockedJobId);
    byte[] content = "temporary private import bytes".getBytes(StandardCharsets.UTF_8);
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -10);
      insertImportJobFixture(
          blockedJobId, actorId, library.id(), UUID.randomUUID(), version, "uploaded", content, 4);
      insertImportJobFixture(
          expiredUploadedId,
          actorId,
          library.id(),
          UUID.randomUUID(),
          version,
          "uploaded",
          content,
          1);
      insertImportJobFixture(
          expiredPendingId,
          actorId,
          library.id(),
          UUID.randomUUID(),
          version,
          "upload_pending",
          content,
          1);
      insertImportJobFixture(
          activeUploadedId,
          actorId,
          library.id(),
          UUID.randomUUID(),
          version,
          "uploaded",
          content,
          0);
      insertQueuedParseJobFixture(expiredUploadedId);

      importStagingFileStore.store(
          expiredUploadedId, new ByteArrayInputStream(content), content.length);
      importStagingFileStore.store(
          activeUploadedId, new ByteArrayInputStream(content), content.length);
      Files.createDirectories(blockedTarget);
      Files.write(blockedTarget.resolve("keep.bin"), content);

      assertThrows(IllegalStateException.class, importStagingCleanupService::cleanupExpiredStaging);
      assertEquals("expired", importJobStatus(blockedJobId));
      assertNull(importJobStagingFileDeletedAt(blockedJobId));
      assertTrue(Files.exists(blockedTarget.resolve("keep.bin")));
      assertEquals("expired", importJobStatus(expiredUploadedId));
      assertEquals("expired", importJobStatus(expiredPendingId));
      assertEquals(
          0,
          importParseJobCount(expiredUploadedId),
          "expiry cleanup must remove private parser preview/queue state before reclaiming the upload");

      Files.delete(blockedTarget.resolve("keep.bin"));
      Files.delete(blockedTarget);
      assertEquals(3, importStagingCleanupService.cleanupExpiredStaging());

      assertFalse(Files.exists(importStagingFileStore.targetPath(expiredUploadedId)));
      assertFalse(Files.exists(importStagingFileStore.targetPath(expiredPendingId)));
      assertEquals("expired", importJobStatus(expiredUploadedId));
      assertEquals("expired", importJobStatus(expiredPendingId));
      assertTrue(importJobStagingFileDeletedAt(blockedJobId) != null);
      assertTrue(importJobStagingFileDeletedAt(expiredUploadedId) != null);
      assertTrue(importJobStagingFileDeletedAt(expiredPendingId) != null);
      importStagingCleanupService.cleanupExpiredStaging();

      assertEquals("uploaded", importJobStatus(activeUploadedId));
      assertNull(importJobStagingFileDeletedAt(activeUploadedId));
      assertTrue(Files.exists(importStagingFileStore.targetPath(activeUploadedId)));
    } finally {
      if (Files.exists(blockedTarget.resolve("keep.bin")))
        Files.delete(blockedTarget.resolve("keep.bin"));
      Files.deleteIfExists(blockedTarget);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void importCancellationIsOwnerBoundIdempotentAndDefersActiveParserCleanup() throws Exception {
    UUID actorId = createSessionTestUser("Import Cancellation Owner");
    UUID otherActorId = createSessionTestUser("Import Cancellation Other Owner");
    UUID approverId = createSessionTestUser("Import Cancellation Approver");
    var library = libraryService.createLibrary(actorId, "Cancellation test shelf");
    String version = "private-import-cancellation-v1";
    String statementText = "Synthetic test-only import cancellation statement.";
    UUID pendingJobId = UUID.randomUUID();
    UUID queuedJobId = UUID.randomUUID();
    UUID parsingJobId = UUID.randomUUID();
    UUID expiredJobId = UUID.randomUUID();
    byte[] content = "temporary private import cancellation bytes".getBytes(StandardCharsets.UTF_8);
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -10);
      insertImportJobFixture(
          pendingJobId,
          actorId,
          library.id(),
          UUID.randomUUID(),
          version,
          "upload_pending",
          content,
          0);
      insertImportJobFixture(
          queuedJobId, actorId, library.id(), UUID.randomUUID(), version, "uploaded", content, 0);
      insertQueuedParseJobFixture(queuedJobId);
      importStagingFileStore.store(queuedJobId, new ByteArrayInputStream(content), content.length);

      insertImportJobFixture(
          parsingJobId, actorId, library.id(), UUID.randomUUID(), version, "uploaded", content, 0);
      insertParsingParseJobFixture(parsingJobId);
      importStagingFileStore.store(parsingJobId, new ByteArrayInputStream(content), content.length);

      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherActorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      String otherCsrf = csrfTokenService.tokenFor(otherSession.cookieValue());

      MvcResult unauthenticated =
          mockMvc.perform(post("/api/v1/imports/{id}/cancel", pendingJobId)).andReturn();
      assertEquals(403, unauthenticated.getResponse().getStatus());
      assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));
      MvcResult missingCsrf =
          mockMvc
              .perform(post("/api/v1/imports/{id}/cancel", pendingJobId).cookie(actorCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      MvcResult foreignOwner =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", pendingJobId)
                      .cookie(otherCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, otherCsrf))
              .andReturn();
      assertEquals(404, foreignOwner.getResponse().getStatus());
      assertEquals("upload_pending", importJobStatus(pendingJobId));

      MvcResult pendingCancel =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", pendingJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(204, pendingCancel.getResponse().getStatus());
      assertTrue(pendingCancel.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("cancelled", importJobStatus(pendingJobId));
      assertNull(
          importJobTokenHash(pendingJobId), "cancellation must revoke an unused upload token");
      assertEquals(
          204,
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", pendingJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn()
              .getResponse()
              .getStatus(),
          "repeating cancellation must be idempotent");
      JsonNode cancelledStatus =
          objectMapper.readTree(
              mockMvc
                  .perform(get("/api/v1/imports/{id}", pendingJobId).cookie(actorCookie))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertEquals("cancelled", cancelledStatus.path("status").asText());
      MvcResult parseCancelled =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/parse", pendingJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(409, parseCancelled.getResponse().getStatus());
      assertEquals(
          "IMPORT_CANCELLED",
          objectMapper
              .readTree(parseCancelled.getResponse().getContentAsString())
              .path("code")
              .asText());
      assertEquals(
          "IMPORT_CANCELLED",
          objectMapper
              .readTree(
                  mockMvc
                      .perform(
                          get("/api/v1/imports/{id}/preview", pendingJobId).cookie(actorCookie))
                      .andReturn()
                      .getResponse()
                      .getContentAsString())
              .path("code")
              .asText());
      assertEquals(
          "IMPORT_CANCELLED",
          objectMapper
              .readTree(
                  mockMvc
                      .perform(
                          post("/api/v1/imports/{id}/commit", pendingJobId)
                              .cookie(actorCookie)
                              .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                              .header("If-Match", "\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"")
                              .header("Idempotency-Key", "import-cancel-commit-reject-01"))
                      .andReturn()
                      .getResponse()
                      .getContentAsString())
              .path("code")
              .asText());
      assertEquals(1, importStagingCleanupService.cleanupExpiredStaging());
      assertNotNull(importJobStagingFileDeletedAt(pendingJobId));

      MvcResult queuedCancel =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", queuedJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(204, queuedCancel.getResponse().getStatus());
      assertEquals(0, importParseJobCount(queuedJobId));
      assertTrue(Files.exists(importStagingFileStore.targetPath(queuedJobId)));
      assertEquals(1, importStagingCleanupService.cleanupExpiredStaging());
      assertFalse(Files.exists(importStagingFileStore.targetPath(queuedJobId)));
      assertNotNull(importJobStagingFileDeletedAt(queuedJobId));

      MvcResult parsingCancel =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", parsingJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(204, parsingCancel.getResponse().getStatus());
      assertEquals("cancelled", importJobStatus(parsingJobId));
      assertEquals("parsing", importParseJobStatus(parsingJobId));
      assertFalse(importParseService.processNext(), "cancelled jobs must not be claimed again");
      importStagingCleanupService.cleanupExpiredStaging();
      assertNull(
          importJobStagingFileDeletedAt(parsingJobId),
          "the janitor must wait for an in-flight parser lease before deleting its source file");
      assertTrue(Files.exists(importStagingFileStore.targetPath(parsingJobId)));

      insertImportJobFixture(
          expiredJobId, actorId, library.id(), UUID.randomUUID(), version, "uploaded", content, 1);
      MvcResult expiredCancel =
          mockMvc
              .perform(
                  post("/api/v1/imports/{id}/cancel", expiredJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf))
              .andReturn();
      assertEquals(409, expiredCancel.getResponse().getStatus());
      assertEquals(
          "IMPORT_NOT_CANCELLABLE",
          objectMapper
              .readTree(expiredCancel.getResponse().getContentAsString())
              .path("code")
              .asText());
      assertEquals("uploaded", importJobStatus(expiredJobId));
    } finally {
      importStagingFileStore.delete(pendingJobId);
      importStagingFileStore.delete(queuedJobId);
      importStagingFileStore.delete(parsingJobId);
      importStagingFileStore.delete(expiredJobId);
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteLibrary(library.id());
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(actorId);
      deleteSessionTestUser(otherActorId);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void importQuotaSerializesConcurrentCreatesAndPreservesRejectedReceipts() throws Exception {
    UUID actorId = createSessionTestUser("Concurrent Import Quota Owner");
    UUID approverId = createSessionTestUser("Concurrent Import Quota Approver");
    var library = libraryService.createLibrary(actorId, "Concurrent quota shelf");
    String version = "private-import-quota-concurrency-v1";
    String statementText = "Synthetic test-only concurrent import quota statement.";
    List<UUID> receiptIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      for (UUID receiptId : receiptIds) {
        insertImportRightsAcceptance(actorId, receiptId, version, statementText);
      }

      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      CountDownLatch ready = new CountDownLatch(receiptIds.size());
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(receiptIds.size());
      try {
        List<Future<Integer>> requests = new ArrayList<>();
        for (UUID receiptId : receiptIds) {
          requests.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS))
                      throw new IllegalStateException("start latch timed out");
                    return mockMvc
                        .perform(
                            post("/api/v1/imports")
                                .cookie(actorCookie)
                                .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    importCreateJson(
                                        library.id(),
                                        receiptId,
                                        version,
                                        "quota-concurrent.txt",
                                        "text/plain",
                                        100,
                                        true)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                  }));
        }
        assertTrue(
            ready.await(10, TimeUnit.SECONDS), "concurrent import requests did not become ready");
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> request : requests) statuses.add(request.get(30, TimeUnit.SECONDS));
        assertEquals(2, statuses.stream().filter(status -> status == 201).count());
        assertEquals(1, statuses.stream().filter(status -> status == 429).count());
      } finally {
        executor.shutdownNow();
      }

      assertEquals(
          2, importJobCount(actorId), "concurrent creates must not overshoot the active-job cap");
      assertEquals(
          2,
          receiptIds.stream()
              .filter(
                  receiptId -> {
                    try {
                      return importRightsAcceptanceConsumedAt(receiptId).isPresent();
                    } catch (SQLException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .count());
      assertEquals(
          1,
          receiptIds.stream()
              .filter(
                  receiptId -> {
                    try {
                      return importRightsAcceptanceConsumedAt(receiptId).isEmpty();
                    } catch (SQLException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .count(),
          "quota-rejected rights receipt must remain available");
    } finally {
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteLibrary(library.id());
      deleteSessionTestUser(actorId);
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void importByteQuotaIncludesExpiredFilesUntilCleanupThenAllowsRetry() throws Exception {
    UUID actorId = createSessionTestUser("Import Byte Quota Owner");
    UUID approverId = createSessionTestUser("Import Byte Quota Approver");
    var library = libraryService.createLibrary(actorId, "Byte quota shelf");
    String version = "private-import-quota-bytes-v1";
    String statementText = "Synthetic test-only import byte quota statement.";
    UUID expiredJobId = UUID.randomUUID();
    UUID expiredReceiptId = UUID.randomUUID();
    UUID retryReceiptId = UUID.randomUUID();
    byte[] expiredContent = new byte[1024];
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      insertImportJobFixture(
          expiredJobId,
          actorId,
          library.id(),
          expiredReceiptId,
          version,
          "uploaded",
          expiredContent,
          1);
      insertImportRightsAcceptance(actorId, retryReceiptId, version, statementText);
      importStagingFileStore.store(
          expiredJobId, new ByteArrayInputStream(expiredContent), expiredContent.length);

      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      String requestBody =
          importCreateJson(
              library.id(), retryReceiptId, version, "after-cleanup.txt", "text/plain", 1, true);

      MvcResult quotaRejected =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody))
              .andReturn();
      assertEquals(429, quotaRejected.getResponse().getStatus());
      assertTrue(
          quotaRejected
              .getResponse()
              .getContentAsString()
              .contains("IMPORT_STAGING_QUOTA_EXCEEDED"));
      assertTrue(importRightsAcceptanceConsumedAt(retryReceiptId).isEmpty());
      assertTrue(Files.exists(importStagingFileStore.targetPath(expiredJobId)));

      assertEquals(1, importStagingCleanupService.cleanupExpiredStaging());
      assertFalse(Files.exists(importStagingFileStore.targetPath(expiredJobId)));
      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody))
              .andReturn();
      assertEquals(201, retry.getResponse().getStatus());
      assertTrue(importRightsAcceptanceConsumedAt(retryReceiptId).isPresent());
    } finally {
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteLibrary(library.id());
      deleteSessionTestUser(actorId);
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void importCreateAndUploadRateLimitsRejectBeforeReceiptConsumptionOrFileWrites()
      throws Exception {
    UUID actorId = createSessionTestUser("Import Rate Limit Owner");
    UUID approverId = createSessionTestUser("Import Rate Limit Approver");
    var library = libraryService.createLibrary(actorId, "Rate limit shelf");
    String version = "private-import-rate-limit-v1";
    String statementText = "Synthetic test-only import rate-limit statement.";
    UUID initialReceiptId = UUID.randomUUID();
    UUID blockedReceiptId = UUID.randomUUID();
    UUID createdJobId = null;
    try {
      insertImportRightsStatement(version, statementText, approverId, "APPROVED", -1);
      addCurrentPolicyAcceptances(actorId);
      insertImportRightsAcceptance(actorId, initialReceiptId, version, statementText);
      insertImportRightsAcceptance(actorId, blockedReceiptId, version, statementText);

      SessionService.IssuedSession actorSession = sessionService.create(actorId).orElseThrow();
      jakarta.servlet.http.Cookie actorCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", actorSession.cookieValue());
      String actorCsrf = csrfTokenService.tokenFor(actorSession.cookieValue());
      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              initialReceiptId,
                              version,
                              "rate-limited.txt",
                              "text/plain",
                              8,
                              true)))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
      createdJobId = UUID.fromString(createdBody.path("job").path("id").asText());
      String uploadToken =
          createdBody.path("uploadTarget").path("headers").path("X-Import-Upload-Token").asText();

      for (int index = 1; index < 20; index++) {
        assertTrue(
            importStagingRateLimiter
                .tryAcquire(actorId, ImportStagingRateLimiter.Action.CREATE)
                .allowed());
      }
      MvcResult createLimited =
          mockMvc
              .perform(
                  post("/api/v1/imports")
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          importCreateJson(
                              library.id(),
                              blockedReceiptId,
                              version,
                              "blocked.txt",
                              "text/plain",
                              8,
                              true)))
              .andReturn();
      assertEquals(429, createLimited.getResponse().getStatus());
      assertTrue(
          createLimited.getResponse().getContentAsString().contains("IMPORT_CREATE_RATE_LIMITED"));
      assertTrue(Integer.parseInt(createLimited.getResponse().getHeader("Retry-After")) > 0);
      assertTrue(importRightsAcceptanceConsumedAt(blockedReceiptId).isEmpty());
      assertEquals(1, importJobCount(actorId));

      for (int index = 0; index < 20; index++) {
        assertTrue(
            importStagingRateLimiter
                .tryAcquire(actorId, ImportStagingRateLimiter.Action.UPLOAD)
                .allowed());
      }
      byte[] uploadBytes = "blocked".getBytes(StandardCharsets.UTF_8);
      MvcResult uploadLimited =
          mockMvc
              .perform(
                  put("/api/v1/imports/{id}/content", createdJobId)
                      .cookie(actorCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, actorCsrf)
                      .header("X-Import-Upload-Token", uploadToken)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(uploadBytes))
              .andReturn();
      assertEquals(429, uploadLimited.getResponse().getStatus());
      assertTrue(
          uploadLimited.getResponse().getContentAsString().contains("IMPORT_UPLOAD_RATE_LIMITED"));
      assertTrue(Integer.parseInt(uploadLimited.getResponse().getHeader("Retry-After")) > 0);
      assertEquals("upload_pending", importJobStatus(createdJobId));
      assertTrue(
          Arrays.equals(
              sha256(uploadToken.getBytes(StandardCharsets.US_ASCII)),
              importJobTokenHash(createdJobId)));
      assertFalse(Files.exists(importStagingFileStore.targetPath(createdJobId)));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement ageBuckets =
              connection.prepareStatement(
                  """
                         UPDATE import_actor_rate_limits
                         SET last_seen_at = clock_timestamp() - INTERVAL '25 hours'
                         WHERE user_id = ?
                         """)) {
        ageBuckets.setObject(1, actorId);
        assertEquals(2, ageBuckets.executeUpdate());
      }
      assertEquals(2, importStagingRateLimiter.purgeInactiveBuckets());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement count =
              connection.prepareStatement(
                  "SELECT COUNT(*) FROM import_actor_rate_limits WHERE user_id = ?")) {
        count.setObject(1, actorId);
        try (ResultSet row = count.executeQuery()) {
          assertTrue(row.next());
          assertEquals(0, row.getInt(1), "inactive actor buckets must be purged");
        }
      }
    } finally {
      deleteImportJobs(actorId);
      deleteImportRightsTestAcceptances(actorId);
      deleteLibrary(library.id());
      deleteSessionTestUser(actorId);
      deleteImportRightsTestStatement(version);
      deleteSessionTestUser(approverId);
    }
  }

  @Test
  void personalLibraryStateIsActorScopedVersionedAndIndependentOfWorkLifecycle() throws Exception {
    UUID ownerId = createSessionTestUser("Library State Owner");
    UUID otherUserId = createSessionTestUser("Other Library State Owner");
    UUID workId = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "State Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "State Test Novel",
              "Author",
              "vi",
              sha256("library-state-work"));
      workId = work.id();
      setWorkStatus(workId, "archived");

      MvcResult unauthenticated =
          mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId)).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());
      assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());

      MvcResult policyBlocked =
          mockMvc.perform(get("/api/v1/me/library/collections").cookie(ownerCookie)).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(otherUserId);

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/me/library/collections")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"Must Not Be Stored\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));
      assertTrue(libraryUserStateService.listCollections(ownerId).isEmpty());

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/me/library/collections")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"  Weekend Reads  \"}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode collectionBody = objectMapper.readTree(created.getResponse().getContentAsString());
      UUID collectionId = UUID.fromString(collectionBody.path("id").asText());
      assertEquals("Weekend Reads", collectionBody.path("name").asText());
      assertEquals(0, collectionBody.path("workCount").asInt());

      MvcResult initialState =
          mockMvc
              .perform(get("/api/v1/me/works/{workId}/library-state", workId).cookie(ownerCookie))
              .andReturn();
      assertEquals(200, initialState.getResponse().getStatus());
      assertEquals("\"v0\"", initialState.getResponse().getHeader("ETag"));
      JsonNode initialBody = objectMapper.readTree(initialState.getResponse().getContentAsString());
      assertEquals("want-to-read", initialBody.path("readingStatus").asText());
      assertEquals(0, initialBody.path("version").asLong());
      assertTrue(initialBody.path("collectionIds").isEmpty());
      assertTrue(initialBody.path("updatedAt").isNull());

      MvcResult saved =
          mockMvc
              .perform(
                  put("/api/v1/me/works/{workId}/library-state", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"readingStatus\":\"reading\",\"collectionIds\":[\""
                              + collectionId
                              + "\"]}")
                      .header("If-Match", "\"v0\"")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, saved.getResponse().getStatus());
      assertEquals("\"v1\"", saved.getResponse().getHeader("ETag"));
      JsonNode savedBody = objectMapper.readTree(saved.getResponse().getContentAsString());
      assertEquals("reading", savedBody.path("readingStatus").asText());
      assertEquals(1, savedBody.path("version").asLong());
      assertEquals(collectionId.toString(), savedBody.path("collectionIds").get(0).asText());
      assertEquals(
          "archived",
          libraryService.findOwnedWork(ownerId, workId).orElseThrow().status(),
          "personal reading state must not change work lifecycle status");

      MvcResult retryAfterLostResponse =
          mockMvc
              .perform(
                  put("/api/v1/me/works/{workId}/library-state", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"readingStatus\":\"reading\",\"collectionIds\":[\""
                              + collectionId
                              + "\"]}")
                      .header("If-Match", "\"v0\"")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, retryAfterLostResponse.getResponse().getStatus());
      assertEquals("\"v1\"", retryAfterLostResponse.getResponse().getHeader("ETag"));

      MvcResult stale =
          mockMvc
              .perform(
                  put("/api/v1/me/works/{workId}/library-state", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"readingStatus\":\"completed\",\"collectionIds\":[]}")
                      .header("If-Match", "\"v0\"")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, stale.getResponse().getStatus());
      assertTrue(stale.getResponse().getContentAsString().contains("LIBRARY_STATE_VERSION_STALE"));
      assertEquals(
          1,
          objectMapper
              .readTree(stale.getResponse().getContentAsString())
              .path("latest")
              .path("version")
              .asLong());

      MvcResult otherUserRead =
          mockMvc
              .perform(
                  get("/api/v1/me/works/{workId}/library-state", workId)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", otherSession.cookieValue())))
              .andReturn();
      assertEquals(404, otherUserRead.getResponse().getStatus());

      LibraryUserStateRepository.CollectionRecord sameNameForOtherActor =
          libraryUserStateService.createCollection(otherUserId, "Weekend Reads");
      assertEquals("Weekend Reads", sameNameForOtherActor.name());
      assertEquals(1, libraryUserStateService.listCollections(otherUserId).size());
      assertThrows(
          LibraryUserStateRepository.CollectionNameConflictException.class,
          () -> libraryUserStateService.createCollection(ownerId, "weekend reads"));

      for (int index = 1; index < LibraryUserStateService.MAX_COLLECTIONS; index++) {
        libraryUserStateService.createCollection(ownerId, "Reading List " + index);
      }
      assertEquals(12, libraryUserStateService.listCollections(ownerId).size());
      assertThrows(
          LibraryUserStateRepository.CollectionLimitReachedException.class,
          () -> libraryUserStateService.createCollection(ownerId, "Thirteenth List"));

      MvcResult deleted =
          mockMvc
              .perform(
                  delete("/api/v1/me/library/collections/{collectionId}", collectionId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, deleted.getResponse().getStatus());
      MvcResult stateAfterCollectionDelete =
          mockMvc
              .perform(get("/api/v1/me/works/{workId}/library-state", workId).cookie(ownerCookie))
              .andReturn();
      assertEquals(200, stateAfterCollectionDelete.getResponse().getStatus());
      assertEquals("\"v2\"", stateAfterCollectionDelete.getResponse().getHeader("ETag"));
      JsonNode afterDelete =
          objectMapper.readTree(stateAfterCollectionDelete.getResponse().getContentAsString());
      assertEquals("reading", afterDelete.path("readingStatus").asText());
      assertTrue(afterDelete.path("collectionIds").isEmpty());
    } finally {
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      deleteLibraryUserData(otherUserId, null);
      deleteSessionTestUser(otherUserId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void libraryListRoutesEnforceActorScopePolicyAndSignedPagination() throws Exception {
    UUID ownerId = createSessionTestUser("Library Query Owner");
    UUID readerId = createSessionTestUser("Library Query Reader");
    UUID outsiderId = createSessionTestUser("Library Query Outsider");
    UUID workspaceId = null;
    try {
      libraryService.createLibrary(ownerId, "Personal Shelf");
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Library Query Workspace");
      workspaceId = workspace.id();
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      LibraryRepository.LibraryRecord sharedLibrary =
          libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Shared Shelf");
      LibraryRepository.WorkRecord firstWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              sharedLibrary.id(),
              "Cursor Novel One",
              "Query Author",
              "vi",
              sha256("Cursor Novel One"));
      LibraryRepository.WorkRecord secondWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              sharedLibrary.id(),
              "Cursor Novel Two",
              "Query Author",
              "vi",
              sha256("Cursor Novel Two"));
      libraryService.addTagToWork(ownerId, firstWork.id(), "Shared Story");
      setWorkStatus(firstWork.id(), "archived");
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(readerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie readerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", readerSession.cookieValue());
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());

      assertEquals(
          401, mockMvc.perform(get("/api/v1/libraries")).andReturn().getResponse().getStatus());
      UUID policyBlockedUser = createSessionTestUser("Library Query Policy Gate");
      try {
        SessionService.IssuedSession blockedSession =
            sessionService.create(policyBlockedUser).orElseThrow();
        MvcResult blocked =
            mockMvc
                .perform(
                    get("/api/v1/libraries")
                        .cookie(
                            new jakarta.servlet.http.Cookie(
                                "__Host-novel_session", blockedSession.cookieValue())))
                .andReturn();
        assertEquals(403, blocked.getResponse().getStatus());
        assertTrue(
            blocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      } finally {
        deleteSessionTestUser(policyBlockedUser);
      }

      MvcResult firstLibraries =
          mockMvc
              .perform(get("/api/v1/libraries").param("limit", "1").cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstLibraries.getResponse().getStatus());
      assertTrue(firstLibraries.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", firstLibraries.getResponse().getHeader("Pragma"));
      JsonNode firstLibraryPage =
          objectMapper.readTree(firstLibraries.getResponse().getContentAsString());
      assertEquals(1, firstLibraryPage.path("items").size());
      assertTrue(firstLibraryPage.path("items").get(0).has("id"));
      assertFalse(firstLibraryPage.path("items").get(0).has("ownerUserId"));
      String libraryCursor = firstLibraryPage.path("nextCursor").asText();
      assertFalse(libraryCursor.isBlank());

      MvcResult secondLibraries =
          mockMvc
              .perform(
                  get("/api/v1/libraries")
                      .param("limit", "1")
                      .param("cursor", libraryCursor)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, secondLibraries.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(secondLibraries.getResponse().getContentAsString())
              .path("items")
              .size());

      MvcResult memberLibraries =
          mockMvc.perform(get("/api/v1/libraries").cookie(readerCookie)).andReturn();
      assertEquals(200, memberLibraries.getResponse().getStatus());
      JsonNode memberLibraryPage =
          objectMapper.readTree(memberLibraries.getResponse().getContentAsString());
      assertEquals(1, memberLibraryPage.path("items").size());
      assertEquals(
          sharedLibrary.id().toString(),
          memberLibraryPage.path("items").get(0).path("id").asText());
      MvcResult foreignCursor =
          mockMvc
              .perform(get("/api/v1/libraries").param("cursor", libraryCursor).cookie(readerCookie))
              .andReturn();
      assertEquals(400, foreignCursor.getResponse().getStatus());
      assertTrue(foreignCursor.getResponse().getContentAsString().contains("INVALID_CURSOR"));

      MvcResult firstWorks =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("limit", "1")
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, firstWorks.getResponse().getStatus());
      JsonNode firstWorkPage = objectMapper.readTree(firstWorks.getResponse().getContentAsString());
      assertEquals(1, firstWorkPage.path("items").size());
      assertFalse(firstWorkPage.path("items").get(0).has("ownerUserId"));
      assertFalse(firstWorkPage.path("items").get(0).has("sourceHash"));
      String workCursor = firstWorkPage.path("nextCursor").asText();
      assertFalse(workCursor.isBlank());

      MvcResult secondWorks =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("limit", "1")
                      .param("cursor", workCursor)
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, secondWorks.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(secondWorks.getResponse().getContentAsString())
              .path("items")
              .size());

      MvcResult filteredWorks =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("q", "Cursor Novel One")
                      .param("tag", "shared story")
                      .param("status", "archived")
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, filteredWorks.getResponse().getStatus());
      JsonNode filteredWorkPage =
          objectMapper.readTree(filteredWorks.getResponse().getContentAsString());
      assertEquals(1, filteredWorkPage.path("items").size());
      assertEquals(
          firstWork.id().toString(), filteredWorkPage.path("items").get(0).path("id").asText());

      MvcResult titleFilteredWorks =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("q", "Two")
                      .param("status", "active")
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, titleFilteredWorks.getResponse().getStatus());
      JsonNode titleFilteredPage =
          objectMapper.readTree(titleFilteredWorks.getResponse().getContentAsString());
      assertEquals(1, titleFilteredPage.path("items").size());
      assertEquals(
          secondWork.id().toString(), titleFilteredPage.path("items").get(0).path("id").asText());

      MvcResult mismatchedFilterCursor =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("cursor", workCursor)
                      .param("status", "active")
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(400, mismatchedFilterCursor.getResponse().getStatus());
      assertTrue(
          mismatchedFilterCursor.getResponse().getContentAsString().contains("INVALID_CURSOR"));

      MvcResult tamperedCursor =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .param("cursor", workCursor + "x")
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(400, tamperedCursor.getResponse().getStatus());
      assertTrue(tamperedCursor.getResponse().getContentAsString().contains("INVALID_CURSOR"));

      MvcResult invalidLibraryId =
          mockMvc
              .perform(get("/api/v1/works").param("libraryId", "not-a-uuid").cookie(readerCookie))
              .andReturn();
      assertEquals(400, invalidLibraryId.getResponse().getStatus());

      MvcResult outsiderWorks =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderWorks.getResponse().getStatus());
      MvcResult emptyOutsiderLibraries =
          mockMvc.perform(get("/api/v1/libraries").cookie(outsiderCookie)).andReturn();
      assertEquals(200, emptyOutsiderLibraries.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(emptyOutsiderLibraries.getResponse().getContentAsString())
              .path("items")
              .size());

      workspaceService.revokeMember(ownerId, workspaceId, readerId);
      MvcResult revokedReader =
          mockMvc
              .perform(
                  get("/api/v1/works")
                      .param("libraryId", sharedLibrary.id().toString())
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(404, revokedReader.getResponse().getStatus());
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryUserData(ownerId, null);
      deleteLibraryUserData(readerId, null);
      deleteLibraryUserData(outsiderId, null);
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void workspaceReaderCanChangeOnlyTheirOwnReadingStateWhileMembershipIsActive() throws Exception {
    UUID ownerId = createSessionTestUser("Workspace State Owner");
    UUID readerId = createSessionTestUser("Workspace State Reader");
    UUID workspaceId = null;
    UUID workId = null;
    try {
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Reading State Workspace");
      workspaceId = workspace.id();
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      LibraryRepository.LibraryRecord library =
          libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Shared Works");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "Shared State Novel",
              "Author",
              "vi",
              sha256("shared-state-work"));
      workId = work.id();
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(readerId);

      LibraryUserStateRepository.WorkStateRecord ownerState =
          libraryUserStateService.replaceWorkState(ownerId, workId, 0, "reading", List.of());
      assertEquals(1, ownerState.version());

      SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
      jakarta.servlet.http.Cookie readerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", readerSession.cookieValue());
      String readerCsrf = csrfTokenService.tokenFor(readerSession.cookieValue());
      MvcResult readerInitial =
          mockMvc
              .perform(get("/api/v1/me/works/{workId}/library-state", workId).cookie(readerCookie))
              .andReturn();
      assertEquals(200, readerInitial.getResponse().getStatus());
      assertEquals("\"v0\"", readerInitial.getResponse().getHeader("ETag"));
      assertEquals(
          "want-to-read",
          objectMapper
              .readTree(readerInitial.getResponse().getContentAsString())
              .path("readingStatus")
              .asText());

      MvcResult readerSaved =
          mockMvc
              .perform(
                  put("/api/v1/me/works/{workId}/library-state", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"readingStatus\":\"completed\",\"collectionIds\":[]}")
                      .header("If-Match", "\"v0\"")
                      .header("X-CSRF-Token", readerCsrf)
                      .cookie(readerCookie))
              .andReturn();
      assertEquals(200, readerSaved.getResponse().getStatus());
      assertEquals(
          "completed",
          objectMapper
              .readTree(readerSaved.getResponse().getContentAsString())
              .path("readingStatus")
              .asText());
      assertEquals(
          "reading", libraryUserStateService.getWorkState(ownerId, workId).readingStatus());
      assertEquals(
          "completed", libraryUserStateService.getWorkState(readerId, workId).readingStatus());

      workspaceService.revokeMember(ownerId, workspaceId, readerId);
      MvcResult revokedReader =
          mockMvc
              .perform(get("/api/v1/me/works/{workId}/library-state", workId).cookie(readerCookie))
              .andReturn();
      assertEquals(404, revokedReader.getResponse().getStatus());
    } finally {
      deleteLibraryUserData(readerId, null);
      deleteLibraryUserData(ownerId, workId);
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void authoringCreateIsPrivateCsrfProtectedAndIdempotent() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Draft Owner");
    UUID workId = null;
    try {
      addCurrentPolicyAcceptances(ownerId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String key = "authoring-create-draft-0001";
      String requestBody =
          "{\"title\":\"  The Private Draft  \",\"author\":\"  Pen Name  \",\"language\":\"vi\"}";

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/authoring/manuscripts")
                      .header("Idempotency-Key", key)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/authoring/manuscripts")
                      .header("Idempotency-Key", key)
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
      workId = UUID.fromString(body.path("id").asText());
      assertEquals("draft", body.path("publicationState").asText());
      assertEquals("private", body.path("visibility").asText());
      assertEquals("The Private Draft", body.path("title").asText());
      assertEquals("Pen Name", body.path("author").asText());
      assertFalse(body.has("ownerUserId"));
      assertFalse(body.has("sourceHash"));

      MvcResult replay =
          mockMvc
              .perform(
                  post("/api/v1/authoring/manuscripts")
                      .header("Idempotency-Key", key)
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, replay.getResponse().getStatus());
      assertEquals(
          workId,
          UUID.fromString(
              objectMapper
                  .readTree(replay.getResponse().getContentAsString())
                  .path("id")
                  .asText()));
      assertEquals(
          created.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

      MvcResult keyCollision =
          mockMvc
              .perform(
                  post("/api/v1/authoring/manuscripts")
                      .header("Idempotency-Key", key)
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Different title\",\"language\":\"vi\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, keyCollision.getResponse().getStatus());
      assertTrue(
          keyCollision.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  """
                      SELECT w.owner_user_id, w.source_kind, w.visibility, w.status,
                             l.workspace_id, l.is_default
                      FROM works w JOIN libraries l ON l.id = w.library_id
                      WHERE w.id = ?
                      """)) {
        select.setObject(1, workId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals(ownerId, row.getObject("owner_user_id", UUID.class));
          assertEquals("authored", row.getString("source_kind"));
          assertEquals("private", row.getString("visibility"));
          assertEquals("active", row.getString("status"));
          assertNull(row.getObject("workspace_id"));
          assertTrue(row.getBoolean("is_default"));
        }
      }
      assertEquals(1, authoredWorkCount(ownerId));
    } finally {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement delete =
              connection.prepareStatement(
                  "DELETE FROM authoring_manuscript_creations WHERE actor_user_id = ?")) {
        delete.setObject(1, ownerId);
        delete.executeUpdate();
      }
      deleteLibraryUserData(ownerId, workId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void authoringManuscriptDetailIsOwnerScopedPrivateAndMetadataOnly() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Detail Owner");
    UUID outsiderId = createSessionTestUser("Authoring Detail Outsider");
    UUID ownerWorkId = null;
    UUID outsiderWorkId = null;
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      LibraryRepository.WorkRecord ownerWork =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Private Detail Draft", "Pen Name", "vi", "detail-owner-key-0001");
      ownerWorkId = ownerWork.id();
      libraryService.createPrivateAuthoredChapter(
          ownerId, ownerWorkId, "First chapter", "detail-chapter-key-0001");
      outsiderWorkId =
          libraryService
              .createPrivateAuthoredWork(
                  outsiderId, "Outsider Draft", "Other Pen Name", "en", "detail-outsider-key-0001")
              .id();

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());

      MvcResult ownerRead =
          mockMvc
              .perform(
                  get("/api/v1/authoring/manuscripts/{workId}", ownerWorkId).cookie(ownerCookie))
              .andReturn();
      assertEquals(200, ownerRead.getResponse().getStatus());
      assertTrue(ownerRead.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode body = objectMapper.readTree(ownerRead.getResponse().getContentAsString());
      assertEquals(ownerWorkId.toString(), body.path("id").asText());
      assertEquals("Private Detail Draft", body.path("title").asText());
      assertEquals("Pen Name", body.path("author").asText());
      assertEquals("vi", body.path("language").asText());
      assertEquals("private", body.path("visibility").asText());
      assertEquals("draft", body.path("publicationState").asText());
      assertEquals(1, body.path("chapterCount").asInt());
      assertEquals(1, body.path("metadataVersion").asLong());
      assertEquals("\"manuscript-metadata-1\"", ownerRead.getResponse().getHeader("ETag"));
      assertFalse(body.has("ownerUserId"));
      assertFalse(body.has("sourceHash"));
      assertFalse(body.has("normalizedContent"));

      MvcResult outsiderRead =
          mockMvc
              .perform(
                  get("/api/v1/authoring/manuscripts/{workId}", ownerWorkId).cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderRead.getResponse().getStatus());
      assertTrue(outsiderRead.getResponse().getContentAsString().contains("MANUSCRIPT_NOT_FOUND"));

    } finally {
      deleteLibraryUserData(ownerId, ownerWorkId);
      deleteLibraryUserData(outsiderId, outsiderWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void authoringMetadataUpdateIsPrivateCsrfProtectedVersionedAndRetrySafe() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Metadata Owner");
    UUID outsiderId = createSessionTestUser("Authoring Metadata Outsider");
    UUID workId = null;
    UUID foreignWorkId = null;
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);
      workId =
          libraryService
              .createPrivateAuthoredWork(
                  ownerId, "Original Title", "Original Pen", "vi", "metadata-owner-create-001")
              .id();
      foreignWorkId =
          libraryService
              .createPrivateAuthoredWork(
                  outsiderId, "Foreign Title", "Foreign Pen", "en", "metadata-outsider-create-01")
              .id();
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      String route = "/api/v1/authoring/manuscripts/{workId}";
      String requestBody =
          "{\"title\":\"Edited 🐉 Title\",\"author\":\"New Pen\",\"language\":\"ja\"}";

      MvcResult initial = mockMvc.perform(get(route, workId).cookie(ownerCookie)).andReturn();
      assertEquals(200, initial.getResponse().getStatus());
      assertEquals("\"manuscript-metadata-1\"", initial.getResponse().getHeader("ETag"));

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingPrecondition =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(428, missingPrecondition.getResponse().getStatus());

      MvcResult overlongTitle =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("title", "😀".repeat(301), "author", "Pen", "language", "vi")))
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(400, overlongTitle.getResponse().getStatus());

      MvcResult updated =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, updated.getResponse().getStatus());
      assertEquals("\"manuscript-metadata-2\"", updated.getResponse().getHeader("ETag"));
      assertTrue(updated.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode updatedBody = objectMapper.readTree(updated.getResponse().getContentAsString());
      assertEquals("Edited 🐉 Title", updatedBody.path("title").asText());
      assertEquals("New Pen", updatedBody.path("author").asText());
      assertEquals("ja", updatedBody.path("language").asText());
      assertEquals("private", updatedBody.path("visibility").asText());
      assertEquals("draft", updatedBody.path("publicationState").asText());
      assertEquals(2, updatedBody.path("metadataVersion").asLong());

      MvcResult exactStateRetry =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, exactStateRetry.getResponse().getStatus());
      assertEquals("\"manuscript-metadata-2\"", exactStateRetry.getResponse().getHeader("ETag"));

      MvcResult staleDifferentState =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"title\":\"Stale Private Title\",\"author\":\"Pen\",\"language\":\"vi\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(412, staleDifferentState.getResponse().getStatus());
      assertEquals(
          "\"manuscript-metadata-2\"", staleDifferentState.getResponse().getHeader("ETag"));
      assertTrue(
          staleDifferentState
              .getResponse()
              .getContentAsString()
              .contains("MANUSCRIPT_METADATA_STALE"));
      assertFalse(
          staleDifferentState.getResponse().getContentAsString().contains("Stale Private Title"));

      MvcResult foreignWrite =
          mockMvc
              .perform(
                  put(route, workId)
                      .header("X-CSRF-Token", outsiderCsrf)
                      .header("If-Match", "\"manuscript-metadata-1\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody)
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, foreignWrite.getResponse().getStatus());

      MvcResult finalRead = mockMvc.perform(get(route, workId).cookie(ownerCookie)).andReturn();
      assertEquals(200, finalRead.getResponse().getStatus());
      assertEquals(
          "Edited 🐉 Title",
          objectMapper
              .readTree(finalRead.getResponse().getContentAsString())
              .path("title")
              .asText());
      assertEquals("\"manuscript-metadata-2\"", finalRead.getResponse().getHeader("ETag"));

      UUID concurrentWorkId = workId;
      ExecutorService editors = Executors.newFixedThreadPool(2);
      try {
        CountDownLatch startConcurrentEdits = new CountDownLatch(1);
        Future<Boolean> firstEditor =
            editors.submit(
                () -> {
                  startConcurrentEdits.await();
                  try {
                    return libraryService
                            .updatePrivateAuthoredWorkDetails(
                                ownerId, concurrentWorkId, 2, "Concurrent A", "New Pen", "ja")
                            .metadataVersion()
                        == 3;
                  } catch (LibraryRepository.ManuscriptMetadataConflictException conflict) {
                    return false;
                  }
                });
        Future<Boolean> secondEditor =
            editors.submit(
                () -> {
                  startConcurrentEdits.await();
                  try {
                    return libraryService
                            .updatePrivateAuthoredWorkDetails(
                                ownerId, concurrentWorkId, 2, "Concurrent B", "New Pen", "ja")
                            .metadataVersion()
                        == 3;
                  } catch (LibraryRepository.ManuscriptMetadataConflictException conflict) {
                    return false;
                  }
                });
        startConcurrentEdits.countDown();
        assertNotEquals(
            firstEditor.get(10, TimeUnit.SECONDS), secondEditor.get(10, TimeUnit.SECONDS));
      } finally {
        editors.shutdownNow();
      }
    } finally {
      deleteLibraryUserData(ownerId, workId);
      deleteLibraryUserData(outsiderId, foreignWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void authoringChapterCreateIsPrivateCsrfProtectedIdempotentAndBlank() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Chapter Owner");
    UUID otherOwnerId = createSessionTestUser("Other Authoring Chapter Owner");
    UUID workId = null;
    UUID foreignWorkId = null;
    try {
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Chapter Draft Parent", "Pen Name", "zh-Hans", "chapter-parent-owner-0001");
      workId = work.id();
      UUID authoredWorkId = work.id();
      LibraryRepository.WorkRecord foreignWork =
          libraryService.createPrivateAuthoredWork(
              otherOwnerId,
              "Foreign Chapter Parent",
              "Other Pen Name",
              "vi",
              "chapter-parent-foreign-0001");
      foreignWorkId = foreignWork.id();

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String chapterPath = "/api/v1/authoring/manuscripts/{workId}/chapters";

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Chapter One\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingPolicy =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Chapter One\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingPolicy.getResponse().getStatus());
      assertTrue(
          missingPolicy.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

      addCurrentPolicyAcceptances(ownerId);
      MvcResult first =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\" 第一章 \"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, first.getResponse().getStatus());
      assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
      UUID firstChapterId = UUID.fromString(firstBody.path("id").asText());
      assertEquals(workId, UUID.fromString(firstBody.path("workId").asText()));
      assertEquals(1, firstBody.path("ordinal").asInt());
      assertEquals("第一章", firstBody.path("title").asText());
      assertEquals("zh-Hans", firstBody.path("language").asText());
      assertEquals("draft", firstBody.path("status").asText());
      assertFalse(firstBody.has("ownerUserId"));
      assertFalse(firstBody.has("sourceRevisionId"));

      MvcResult replay =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"第一章\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, replay.getResponse().getStatus());
      assertEquals(
          first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

      MvcResult keyCollision =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Different Chapter\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, keyCollision.getResponse().getStatus());
      assertTrue(
          keyCollision.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      MvcResult second =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-create-0002")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"第二章\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, second.getResponse().getStatus());
      assertEquals(
          2,
          objectMapper.readTree(second.getResponse().getContentAsString()).path("ordinal").asInt());

      ExecutorService chapterCreators = Executors.newFixedThreadPool(4);
      try {
        CountDownLatch startConcurrentCreates = new CountDownLatch(1);
        List<Future<LibraryRepository.ChapterRecord>> concurrentCreates = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
          int requestIndex = index;
          concurrentCreates.add(
              chapterCreators.submit(
                  () -> {
                    startConcurrentCreates.await();
                    return libraryService.createPrivateAuthoredChapter(
                        ownerId,
                        authoredWorkId,
                        "Concurrent Chapter " + requestIndex,
                        "authoring-chapter-concurrent-000" + requestIndex);
                  }));
        }
        startConcurrentCreates.countDown();
        List<Integer> concurrentOrdinals = new ArrayList<>();
        for (Future<LibraryRepository.ChapterRecord> creation : concurrentCreates) {
          concurrentOrdinals.add(creation.get(20, TimeUnit.SECONDS).ordinal());
        }
        assertEquals(List.of(3, 4, 5, 6), concurrentOrdinals.stream().sorted().toList());
      } finally {
        chapterCreators.shutdownNow();
      }

      MvcResult foreign =
          mockMvc
              .perform(
                  post(chapterPath, foreignWorkId)
                      .header("Idempotency-Key", "authoring-chapter-foreign-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Hidden\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, foreign.getResponse().getStatus());

      setWorkSourceKind(workId, "imported");
      MvcResult imported =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-imported-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Hidden\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, imported.getResponse().getStatus());
      setWorkSourceKind(workId, "authored");

      setWorkVisibility(workId, "public");
      MvcResult publicWork =
          mockMvc
              .perform(
                  post(chapterPath, workId)
                      .header("Idempotency-Key", "authoring-chapter-public-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"title\":\"Hidden\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, publicWork.getResponse().getStatus());
      setWorkVisibility(workId, "private");

      MvcResult unreadableContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapterId).cookie(ownerCookie))
              .andReturn();
      assertEquals(404, unreadableContent.getResponse().getStatus());

      assertThrows(
          IllegalStateException.class,
          () ->
              libraryRepository.createPrivateAuthoredChapter(
                  ownerId,
                  authoredWorkId,
                  "Must Roll Back",
                  "invalid-key",
                  sha256("rollback-after-chapter-insert")));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  """
                      SELECT
                        (SELECT COUNT(*) FROM chapters WHERE work_id = ?) AS chapters,
                        (SELECT COUNT(*) FROM volumes WHERE work_id = ?) AS volumes,
                        (SELECT COUNT(*) FROM chapter_revisions r JOIN chapters c
                          ON c.id = r.chapter_id WHERE c.work_id = ?) AS revisions,
                        (SELECT COUNT(*) FROM segments s JOIN chapter_revisions r
                          ON r.id = s.chapter_revision_id JOIN chapters c
                          ON c.id = r.chapter_id WHERE c.work_id = ?) AS segments,
                        (SELECT COUNT(*) FROM authoring_chapter_creations WHERE work_id = ?) AS receipts
                      """)) {
        for (int index = 1; index <= 5; index++) select.setObject(index, workId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals(6, row.getInt("chapters"));
          assertEquals(0, row.getInt("volumes"));
          assertEquals(0, row.getInt("revisions"));
          assertEquals(0, row.getInt("segments"));
          assertEquals(6, row.getInt("receipts"));
        }
      }
    } finally {
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      if (foreignWorkId != null) deleteLibraryUserData(otherOwnerId, foreignWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void authoringDraftRevisionsArePrivateVersionedIdempotentAndReaderHidden() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Revision Owner");
    UUID otherOwnerId = createSessionTestUser("Other Authoring Revision Owner");
    UUID workId = null;
    UUID foreignWorkId = null;
    try {
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Revision Parent", "Pen Name", "vi", "revision-parent-owner-0001");
      workId = work.id();
      LibraryRepository.ChapterRecord chapter =
          libraryService.createPrivateAuthoredChapter(
              ownerId, workId, "Chapter One", "revision-chapter-owner-0001");
      UUID chapterId = chapter.id();
      UUID authoredWorkId = work.id();
      LibraryRepository.WorkRecord foreignWork =
          libraryService.createPrivateAuthoredWork(
              otherOwnerId,
              "Foreign Revision Parent",
              "Other Pen Name",
              "vi",
              "revision-parent-foreign-0001");
      foreignWorkId = foreignWork.id();
      LibraryRepository.ChapterRecord foreignChapter =
          libraryService.createPrivateAuthoredChapter(
              otherOwnerId, foreignWorkId, "Foreign Chapter", "revision-chapter-foreign-0001");

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String draftPath = "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/draft";
      String revisionPath = "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/revisions";

      MvcResult policyBlockedRead =
          mockMvc.perform(get(draftPath, workId, chapterId).cookie(ownerCookie)).andReturn();
      assertEquals(403, policyBlockedRead.getResponse().getStatus());
      assertTrue(
          policyBlockedRead
              .getResponse()
              .getContentAsString()
              .contains("POLICY_ACCEPTANCE_REQUIRED"));

      addCurrentPolicyAcceptances(ownerId);
      MvcResult anonymousRead = mockMvc.perform(get(draftPath, workId, chapterId)).andReturn();
      assertEquals(401, anonymousRead.getResponse().getStatus());

      MvcResult initial =
          mockMvc.perform(get(draftPath, workId, chapterId).cookie(ownerCookie)).andReturn();
      assertEquals(200, initial.getResponse().getStatus());
      assertEquals("\"draft-0\"", initial.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(initial.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode initialBody = objectMapper.readTree(initial.getResponse().getContentAsString());
      assertEquals(0, initialBody.path("revisionNo").asInt());
      assertEquals("", initialBody.path("normalizedContent").asText());
      assertTrue(initialBody.path("segments").isEmpty());

      String firstBody =
          "{\"sourceFormat\":\"plain_text\",\"normalizedContent\":\"First paragraph.\","
              + "\"segments\":[{\"ordinal\":1,\"type\":\"paragraph\","
              + "\"sourceText\":\"First paragraph.\","
              + "\"stableAnchor\":\"chapter-one-p1\"}]}";
      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-0\"")
                      .header("Idempotency-Key", "authoring-draft-save-0001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult foreign =
          mockMvc
              .perform(get(draftPath, foreignWorkId, foreignChapter.id()).cookie(ownerCookie))
              .andReturn();
      assertEquals(404, foreign.getResponse().getStatus());
      assertTrue(foreign.getResponse().getContentAsString().contains("DRAFT_CHAPTER_NOT_FOUND"));

      MvcResult foreignWrite =
          mockMvc
              .perform(
                  post(revisionPath, foreignWorkId, foreignChapter.id())
                      .header(HttpHeaders.IF_MATCH, "\"draft-0\"")
                      .header("Idempotency-Key", "authoring-draft-foreign-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, foreignWrite.getResponse().getStatus());
      assertTrue(
          foreignWrite.getResponse().getContentAsString().contains("DRAFT_CHAPTER_NOT_FOUND"));

      assertThrows(
          IllegalStateException.class,
          () ->
              libraryRepository.savePrivateAuthoredDraftRevision(
                  ownerId,
                  authoredWorkId,
                  chapterId,
                  0,
                  "invalid-key",
                  sha256("rollback-draft-revision-request"),
                  "plain_text",
                  "Rollback body.",
                  sha256("Rollback body."),
                  List.of(
                      new LibraryRepository.SegmentInput(
                          1,
                          "paragraph",
                          "Rollback body.",
                          "rollback-p1",
                          sha256("Rollback body.")))));

      MvcResult first =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-0\"")
                      .header("Idempotency-Key", "authoring-draft-save-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, first.getResponse().getStatus());
      assertEquals("\"draft-1\"", first.getResponse().getHeader(HttpHeaders.ETAG));
      assertTrue(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      JsonNode firstResponse = objectMapper.readTree(first.getResponse().getContentAsString());
      assertEquals(1, firstResponse.path("revisionNo").asInt());
      assertFalse(firstResponse.has("normalizedContent"));

      MvcResult replay =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-0\"")
                      .header("Idempotency-Key", "authoring-draft-save-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, replay.getResponse().getStatus());
      assertEquals(
          first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
      assertEquals(
          first.getResponse().getHeader(HttpHeaders.ETAG),
          replay.getResponse().getHeader(HttpHeaders.ETAG));

      MvcResult keyCollision =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-0\"")
                      .header("Idempotency-Key", "authoring-draft-save-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody.replace("First paragraph.", "Changed body."))
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, keyCollision.getResponse().getStatus());
      assertTrue(
          keyCollision.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      String secondBody =
          "{\"sourceFormat\":\"plain_text\",\"normalizedContent\":\"Second paragraph.\","
              + "\"segments\":[{\"ordinal\":1,\"type\":\"paragraph\","
              + "\"sourceText\":\"Second paragraph.\","
              + "\"stableAnchor\":\"chapter-one-p1\"}]}";
      MvcResult second =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-1\"")
                      .header("Idempotency-Key", "authoring-draft-save-0002")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(secondBody)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, second.getResponse().getStatus());
      assertEquals("\"draft-2\"", second.getResponse().getHeader(HttpHeaders.ETAG));

      MvcResult stale =
          mockMvc
              .perform(
                  post(revisionPath, workId, chapterId)
                      .header(HttpHeaders.IF_MATCH, "\"draft-1\"")
                      .header("Idempotency-Key", "authoring-draft-stale-0001")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstBody.replace("First paragraph.", "Third paragraph."))
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(412, stale.getResponse().getStatus());
      assertTrue(stale.getResponse().getContentAsString().contains("DRAFT_REVISION_STALE"));
      assertTrue(stale.getResponse().getContentAsString().contains("\\\"draft-2\\\""));

      MvcResult current =
          mockMvc.perform(get(draftPath, workId, chapterId).cookie(ownerCookie)).andReturn();
      assertEquals(200, current.getResponse().getStatus());
      assertEquals("\"draft-2\"", current.getResponse().getHeader(HttpHeaders.ETAG));
      JsonNode currentBody = objectMapper.readTree(current.getResponse().getContentAsString());
      assertEquals("Second paragraph.", currentBody.path("normalizedContent").asText());
      assertEquals(2, currentBody.path("revisionNo").asInt());
      assertEquals(
          "Second paragraph.", currentBody.path("segments").get(0).path("sourceText").asText());

      MvcResult outline =
          mockMvc
              .perform(
                  get("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, outline.getResponse().getStatus());
      JsonNode outlineBody = objectMapper.readTree(outline.getResponse().getContentAsString());
      assertFalse(outlineBody.path("items").get(0).path("contentAvailable").asBoolean());
      assertFalse(outline.getResponse().getContentAsString().contains("Second paragraph."));

      MvcResult readerContent =
          mockMvc
              .perform(get("/api/v1/chapters/{chapterId}/content", chapterId).cookie(ownerCookie))
              .andReturn();
      assertEquals(404, readerContent.getResponse().getStatus());
      assertTrue(
          readerContent.getResponse().getContentAsString().contains("\"code\":\"NOT_FOUND\""));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  """
                      SELECT c.status, c.source_revision_id,
                             (SELECT COUNT(*) FROM chapter_revisions r WHERE r.chapter_id = c.id) AS revisions,
                             (SELECT COUNT(*) FROM segments s JOIN chapter_revisions r
                               ON r.id = s.chapter_revision_id WHERE r.chapter_id = c.id) AS segments,
                             (SELECT COUNT(*) FROM authoring_chapter_revision_writes writes
                               WHERE writes.chapter_id = c.id) AS receipts
                      FROM chapters c WHERE c.id = ?
                      """)) {
        select.setObject(1, chapterId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals("draft", row.getString("status"));
          assertNotNull(row.getObject("source_revision_id", UUID.class));
          assertEquals(2, row.getInt("revisions"));
          assertEquals(2, row.getInt("segments"));
          assertEquals(2, row.getInt("receipts"));
        }
      }
    } finally {
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      if (foreignWorkId != null) deleteLibraryUserData(otherOwnerId, foreignWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  private void setWorkSourceKind(UUID workId, String sourceKind) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE works SET source_kind = ? WHERE id = ?")) {
      update.setString(1, sourceKind);
      update.setObject(2, workId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void setWorkVisibility(UUID workId, String visibility) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE works SET visibility = ? WHERE id = ?")) {
      update.setString(1, visibility);
      update.setObject(2, workId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private SyntheticPublicationScopeFixture approveSyntheticPublicationScopes(
      UUID ownerId, UUID workId, String... scopeCodes) throws Exception {
    UUID reviewerId = createSessionTestUser("Synthetic publication scope reviewer");
    String policyVersion =
        "synthetic-publication-access-" + UUID.randomUUID().toString().replace("-", "");
    String policyText =
        "Synthetic PostgreSQL fixture only; not legal terms and grants no real distribution rights.";
    byte[] policyDigest = sha256(policyText);
    String manuscriptKey = "publication-scope-" + UUID.randomUUID().toString().replace("-", "");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE works SET source_kind = 'authored', visibility = 'public' WHERE id = ?")) {
          update.setObject(1, workId);
          assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement insertManuscript =
            connection.prepareStatement(
                """
                    INSERT INTO authoring_manuscript_creations (
                        actor_user_id, idempotency_key, request_sha256, work_id)
                    VALUES (?, ?, ?, ?)
                    """)) {
          insertManuscript.setObject(1, ownerId);
          insertManuscript.setString(2, manuscriptKey);
          insertManuscript.setBytes(3, sha256(manuscriptKey));
          insertManuscript.setObject(4, workId);
          assertEquals(1, insertManuscript.executeUpdate());
        }
        try (PreparedStatement insertReviewerRole =
            connection.prepareStatement(
                """
                    INSERT INTO security_role_assignments (
                        id, user_id, role_code, assigned_by_user_id, reason_code)
                    VALUES (?, ?, 'publication_reviewer', ?, 'REVIEW_CAPACITY')
                    """)) {
          insertReviewerRole.setObject(1, UUID.randomUUID());
          insertReviewerRole.setObject(2, reviewerId);
          insertReviewerRole.setObject(3, ownerId);
          assertEquals(1, insertReviewerRole.executeUpdate());
        }
        try (PreparedStatement insertPolicy =
            connection.prepareStatement(
                """
                    INSERT INTO publication_rights_policy_documents (
                        version, effective_at, display_text, policy_sha256)
                    VALUES (?, clock_timestamp() - INTERVAL '1 second', ?, ?)
                    """)) {
          insertPolicy.setString(1, policyVersion);
          insertPolicy.setString(2, policyText);
          insertPolicy.setBytes(3, policyDigest);
          assertEquals(1, insertPolicy.executeUpdate());
        }
        try (PreparedStatement approvePolicy =
            connection.prepareStatement(
                """
                    UPDATE publication_rights_policy_documents
                       SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                     WHERE version = ? AND status = 'DRAFT'
                    """)) {
          approvePolicy.setObject(1, reviewerId);
          approvePolicy.setString(2, policyVersion);
          assertEquals(1, approvePolicy.executeUpdate());
        }
        try (PreparedStatement acceptPolicy =
            connection.prepareStatement(
                """
                    INSERT INTO publication_rights_policy_acceptances (
                        id, user_id, work_id, idempotency_key,
                        policy_version, policy_sha256, source)
                    VALUES (?, ?, ?, ?, ?, ?, 'web-authoring-publication')
                    """)) {
          acceptPolicy.setObject(1, UUID.randomUUID());
          acceptPolicy.setObject(2, ownerId);
          acceptPolicy.setObject(3, workId);
          acceptPolicy.setObject(4, UUID.randomUUID());
          acceptPolicy.setString(5, policyVersion);
          acceptPolicy.setBytes(6, policyDigest);
          assertEquals(1, acceptPolicy.executeUpdate());
        }
        for (String scopeCode : scopeCodes) {
          String snapshot;
          try (PreparedStatement readSnapshot =
              connection.prepareStatement("SELECT publication_work_scope_snapshot(?, ?)::text")) {
            readSnapshot.setObject(1, workId);
            readSnapshot.setString(2, scopeCode);
            try (ResultSet row = readSnapshot.executeQuery()) {
              assertTrue(row.next());
              snapshot = row.getString(1);
              assertNotNull(snapshot);
            }
          }
          try (PreparedStatement insertApproval =
              connection.prepareStatement(
                  """
                      INSERT INTO publication_scope_access_approvals (
                          id, work_id, scope_code, approved_snapshot,
                          policy_version, policy_sha256, approved_by_user_id, reason_code)
                      VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?, ?, 'RIGHTS_REVIEWED')
                      """)) {
            insertApproval.setObject(1, UUID.randomUUID());
            insertApproval.setObject(2, workId);
            insertApproval.setString(3, scopeCode);
            insertApproval.setString(4, snapshot);
            insertApproval.setString(5, policyVersion);
            insertApproval.setBytes(6, policyDigest);
            insertApproval.setObject(7, reviewerId);
            assertEquals(1, insertApproval.executeUpdate());
          }
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        deleteSessionTestUser(reviewerId);
        throw exception;
      }
    }
    return new SyntheticPublicationScopeFixture(reviewerId, policyVersion);
  }

  private void cleanupSyntheticPublicationScopeFixture(SyntheticPublicationScopeFixture fixture)
      throws SQLException {
    if (fixture == null) return;
    deletePublicationRightsPolicyFixture(fixture.policyVersion());
    try (Connection connection = dataSource.getConnection();
        PreparedStatement deleteRole =
            connection.prepareStatement(
                "DELETE FROM security_role_assignments WHERE user_id = ?")) {
      deleteRole.setObject(1, fixture.reviewerId());
      deleteRole.executeUpdate();
    }
    deleteSessionTestUser(fixture.reviewerId());
  }

  private void revokeSyntheticPublicationScope(
      UUID workId, SyntheticPublicationScopeFixture fixture, String scopeCode) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insertRevocation =
            connection.prepareStatement(
                """
                    INSERT INTO publication_scope_access_revocations (
                        approval_id, revoked_by_user_id, reason_code)
                    SELECT id, ?, 'RIGHTS_WITHDRAWN'
                    FROM publication_scope_access_approvals
                    WHERE work_id = ? AND scope_code = ?
                    """)) {
      insertRevocation.setObject(1, fixture.reviewerId());
      insertRevocation.setObject(2, workId);
      insertRevocation.setString(3, scopeCode);
      assertEquals(1, insertRevocation.executeUpdate());
    }
  }

  @Test
  void authoringChapterOutlineListIsPrivatePagedAndCursorBound() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring Outline Owner");
    UUID otherOwnerId = createSessionTestUser("Other Authoring Outline Owner");
    UUID workId = null;
    UUID foreignWorkId = null;
    UUID workspaceId = null;
    UUID workspaceWorkId = null;
    try {
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Outline Parent", "Pen Name", "vi", "outline-parent-owner-0001");
      workId = work.id();
      LibraryRepository.WorkRecord foreignWork =
          libraryService.createPrivateAuthoredWork(
              otherOwnerId,
              "Foreign Outline Parent",
              "Other Pen Name",
              "vi",
              "outline-parent-foreign-0001");
      foreignWorkId = foreignWork.id();
      libraryService.createPrivateAuthoredChapter(
          ownerId, workId, "Chapter One", "outline-chapter-owner-0001");
      libraryService.createPrivateAuthoredChapter(
          ownerId, workId, "Chapter Two", "outline-chapter-owner-0002");

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherOwnerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      String route = "/api/v1/authoring/manuscripts/{workId}/chapters";

      MvcResult policyBlocked =
          mockMvc.perform(get(route, workId).cookie(ownerCookie).param("limit", "1")).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(otherOwnerId);

      MvcResult firstPage =
          mockMvc.perform(get(route, workId).cookie(ownerCookie).param("limit", "1")).andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(1, firstBody.path("items").size());
      assertEquals(1, firstBody.path("items").get(0).path("ordinal").asInt());
      assertEquals("Chapter One", firstBody.path("items").get(0).path("title").asText());
      assertEquals("draft", firstBody.path("items").get(0).path("status").asText());
      assertFalse(firstBody.path("items").get(0).path("contentAvailable").asBoolean());
      String nextCursor = firstBody.path("nextCursor").asText();
      assertFalse(nextCursor.isBlank());
      assertFalse(firstPage.getResponse().getContentAsString().contains("sourceRevisionId"));
      assertFalse(firstPage.getResponse().getContentAsString().contains("ownerUserId"));

      MvcResult secondPage =
          mockMvc
              .perform(
                  get(route, workId)
                      .cookie(ownerCookie)
                      .param("limit", "1")
                      .param("cursor", nextCursor))
              .andReturn();
      assertEquals(200, secondPage.getResponse().getStatus());
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(1, secondBody.path("items").size());
      assertEquals(2, secondBody.path("items").get(0).path("ordinal").asInt());
      assertTrue(secondBody.path("nextCursor").isNull());

      MvcResult foreignCursor =
          mockMvc
              .perform(get(route, workId).cookie(otherCookie).param("cursor", nextCursor))
              .andReturn();
      assertEquals(400, foreignCursor.getResponse().getStatus());
      assertTrue(
          foreignCursor.getResponse().getContentAsString().contains("INVALID_AUTHORING_CURSOR"));

      MvcResult wrongWorkCursor =
          mockMvc
              .perform(get(route, foreignWorkId).cookie(ownerCookie).param("cursor", nextCursor))
              .andReturn();
      assertEquals(400, wrongWorkCursor.getResponse().getStatus());

      MvcResult foreignWorkNotFound =
          mockMvc.perform(get(route, foreignWorkId).cookie(ownerCookie)).andReturn();
      assertEquals(404, foreignWorkNotFound.getResponse().getStatus());

      MvcResult tamperedCursor =
          mockMvc
              .perform(get(route, workId).cookie(ownerCookie).param("cursor", nextCursor + "="))
              .andReturn();
      assertEquals(400, tamperedCursor.getResponse().getStatus());

      MvcResult invalidLimit =
          mockMvc.perform(get(route, workId).cookie(ownerCookie).param("limit", "101")).andReturn();
      assertEquals(400, invalidLimit.getResponse().getStatus());
      assertTrue(
          invalidLimit.getResponse().getContentAsString().contains("INVALID_AUTHORING_QUERY"));

      setWorkSourceKind(workId, "imported");
      assertEquals(
          404,
          mockMvc
              .perform(get(route, workId).cookie(ownerCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      setWorkSourceKind(workId, "authored");
      setWorkVisibility(workId, "public");
      assertEquals(
          404,
          mockMvc
              .perform(get(route, workId).cookie(ownerCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      setWorkVisibility(workId, "private");

      var workspace = workspaceService.createWorkspace(ownerId, "Authoring Outline Workspace");
      workspaceId = workspace.id();
      LibraryRepository.LibraryRecord workspaceLibrary =
          libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Shared Drafts");
      LibraryRepository.WorkRecord workspaceWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              workspaceLibrary.id(),
              "Workspace Work",
              "Pen Name",
              "vi",
              sha256("workspace-authored-work"));
      workspaceWorkId = workspaceWork.id();
      setWorkSourceKind(workspaceWorkId, "authored");
      MvcResult workspaceBackedWork =
          mockMvc.perform(get(route, workspaceWorkId).cookie(ownerCookie)).andReturn();
      assertEquals(404, workspaceBackedWork.getResponse().getStatus());
    } finally {
      if (workspaceWorkId != null) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement deleteWork =
                connection.prepareStatement(
                    "DELETE FROM works WHERE id = ? AND owner_user_id = ?")) {
          deleteWork.setObject(1, workspaceWorkId);
          deleteWork.setObject(2, ownerId);
          deleteWork.executeUpdate();
        }
      }
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      if (foreignWorkId != null) deleteLibraryUserData(otherOwnerId, foreignWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void authoringEmptyDraftChapterDeleteIsPrivateIdempotentAndPreservesSavedContent()
      throws Exception {
    UUID ownerId = createSessionTestUser("Empty Chapter Delete Owner");
    UUID otherOwnerId = createSessionTestUser("Other Empty Chapter Delete Owner");
    UUID workId = null;
    UUID foreignWorkId = null;
    try {
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Delete Empty Chapter", "Pen Name", "vi", "delete-empty-work-00001");
      workId = work.id();
      LibraryRepository.ChapterRecord emptyChapter =
          libraryService.createPrivateAuthoredChapter(
              ownerId, workId, "Never saved", "delete-empty-chapter-00001");
      LibraryRepository.ChapterRecord savedChapter =
          libraryService.createPrivateAuthoredChapter(
              ownerId, workId, "Saved draft", "delete-saved-chapter-00001");
      libraryService.savePrivateAuthoredDraftRevision(
          ownerId,
          workId,
          savedChapter.id(),
          0,
          "delete-saved-revision-0001",
          "plain_text",
          "Private saved text.",
          List.of(new LibraryService.NewSegment(1, "paragraph", "Private saved text.", "p1")));

      LibraryRepository.WorkRecord foreignWork =
          libraryService.createPrivateAuthoredWork(
              otherOwnerId, "Foreign Delete Work", "Other Pen", "en", "delete-foreign-work-0001");
      foreignWorkId = foreignWork.id();
      LibraryRepository.ChapterRecord foreignChapter =
          libraryService.createPrivateAuthoredChapter(
              otherOwnerId, foreignWorkId, "Foreign empty", "delete-foreign-chapter-01");

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      String route = "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}";

      MvcResult anonymous = mockMvc.perform(delete(route, workId, emptyChapter.id())).andReturn();
      assertEquals(403, anonymous.getResponse().getStatus());
      assertTrue(anonymous.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingCsrf =
          mockMvc.perform(delete(route, workId, emptyChapter.id()).cookie(ownerCookie)).andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult missingPolicy =
          mockMvc
              .perform(
                  delete(route, workId, emptyChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingPolicy.getResponse().getStatus());
      assertTrue(
          missingPolicy.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(otherOwnerId);

      MvcResult ownerDelete =
          mockMvc
              .perform(
                  delete(route, workId, emptyChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, ownerDelete.getResponse().getStatus());
      assertTrue(
          ownerDelete.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
      assertEquals("no-cache", ownerDelete.getResponse().getHeader(HttpHeaders.PRAGMA));

      MvcResult uncertainRetry =
          mockMvc
              .perform(
                  delete(route, workId, emptyChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, uncertainRetry.getResponse().getStatus());

      MvcResult foreignChapterRequest =
          mockMvc
              .perform(
                  delete(route, workId, foreignChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, foreignChapterRequest.getResponse().getStatus());
      assertTrue(
          foreignChapterRequest
              .getResponse()
              .getContentAsString()
              .contains("DRAFT_CHAPTER_NOT_FOUND"));

      MvcResult foreignWorkRequest =
          mockMvc
              .perform(
                  delete(route, foreignWorkId, foreignChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, foreignWorkRequest.getResponse().getStatus());

      MvcResult savedConflict =
          mockMvc
              .perform(
                  delete(route, workId, savedChapter.id())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, savedConflict.getResponse().getStatus());
      assertTrue(
          savedConflict.getResponse().getContentAsString().contains("DRAFT_CHAPTER_HAS_CONTENT"));
      assertFalse(savedConflict.getResponse().getContentAsString().contains("Private saved text."));

      MvcResult deletedDraftRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/draft",
                          workId,
                          emptyChapter.id())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, deletedDraftRead.getResponse().getStatus());
      MvcResult savedDraftRead =
          mockMvc
              .perform(
                  get(
                          "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/draft",
                          workId,
                          savedChapter.id())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, savedDraftRead.getResponse().getStatus());
      assertTrue(savedDraftRead.getResponse().getContentAsString().contains("Private saved text."));
      MvcResult readerRead =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", emptyChapter.id())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, readerRead.getResponse().getStatus());

      MvcResult outline =
          mockMvc
              .perform(
                  get("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, outline.getResponse().getStatus());
      JsonNode outlineItems =
          objectMapper.readTree(outline.getResponse().getContentAsString()).path("items");
      assertEquals(1, outlineItems.size());
      assertEquals(savedChapter.id().toString(), outlineItems.get(0).path("id").asText());
      assertTrue(outlineItems.get(0).path("hasSavedDraftContent").asBoolean());
      assertTrue(outline.getResponse().getContentAsString().contains("Saved draft"));
      assertFalse(outline.getResponse().getContentAsString().contains("Private saved text."));

      MvcResult detail =
          mockMvc
              .perform(get("/api/v1/authoring/manuscripts/{workId}", workId).cookie(ownerCookie))
              .andReturn();
      assertEquals(
          1,
          objectMapper
              .readTree(detail.getResponse().getContentAsString())
              .path("chapterCount")
              .asInt());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  "SELECT status, deleted_at FROM chapters WHERE id = ? AND work_id = ?")) {
        select.setObject(1, emptyChapter.id());
        select.setObject(2, workId);
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          assertEquals("deleted", row.getString("status"));
          assertNotNull(row.getObject("deleted_at"));
        }
      }
    } finally {
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      if (foreignWorkId != null) deleteLibraryUserData(otherOwnerId, foreignWorkId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void authoringEmptyDraftDeleteAndFirstSaveHaveOneTransactionalWinner() throws Exception {
    UUID ownerId = createSessionTestUser("Chapter Delete Race Owner");
    UUID workId = null;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Delete Race Work", "Pen Name", "vi", "delete-race-work-000001");
      workId = work.id();
      LibraryRepository.ChapterRecord chapter =
          libraryService.createPrivateAuthoredChapter(
              ownerId, workId, "Race chapter", "delete-race-chapter-00001");
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      UUID finalWorkId = workId;

      Future<String> deletion =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                  libraryService.deletePrivateEmptyAuthoredChapter(
                      ownerId, finalWorkId, chapter.id());
                  return "deleted";
                } catch (LibraryRepository.ChapterHasSavedContentException exception) {
                  return "delete-conflict";
                }
              });
      Future<String> firstSave =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                  libraryService.savePrivateAuthoredDraftRevision(
                      ownerId,
                      finalWorkId,
                      chapter.id(),
                      0,
                      "delete-race-save-000001",
                      "plain_text",
                      "Winner is saved.",
                      List.of(
                          new LibraryService.NewSegment(1, "paragraph", "Winner is saved.", "p1")));
                  return "saved";
                } catch (LibraryRepository.ChapterNotFoundException exception) {
                  return "save-conflict";
                }
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      String deletionResult = deletion.get(15, TimeUnit.SECONDS);
      String saveResult = firstSave.get(15, TimeUnit.SECONDS);
      assertTrue(
          ("deleted".equals(deletionResult) && "save-conflict".equals(saveResult))
              || ("delete-conflict".equals(deletionResult) && "saved".equals(saveResult)));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement select =
              connection.prepareStatement(
                  "SELECT status, deleted_at, source_revision_id FROM chapters WHERE id = ?")) {
        select.setObject(1, chapter.id());
        try (ResultSet row = select.executeQuery()) {
          assertTrue(row.next());
          if ("deleted".equals(deletionResult)) {
            assertEquals("deleted", row.getString("status"));
            assertNotNull(row.getObject("deleted_at"));
            assertNull(row.getObject("source_revision_id"));
          } else {
            assertEquals("draft", row.getString("status"));
            assertNull(row.getObject("deleted_at"));
            assertNotNull(row.getObject("source_revision_id"));
          }
        }
      }
    } finally {
      executor.shutdownNow();
      if (workId != null) deleteLibraryUserData(ownerId, workId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void authoringManuscriptListIsOwnerScopedPrivatePagedAndMetadataOnly() throws Exception {
    UUID ownerId = createSessionTestUser("Authoring List Owner");
    UUID otherOwnerId = createSessionTestUser("Other Authoring List Owner");
    List<UUID> createdWorkIds = new ArrayList<>();
    UUID foreignWorkId = null;
    try {
      LibraryRepository.WorkRecord first =
          libraryService.createPrivateAuthoredWork(
              ownerId, "List Novel One", "Pen Name", "vi", "author-list-owner-0001");
      LibraryRepository.WorkRecord second =
          libraryService.createPrivateAuthoredWork(
              ownerId, "List Novel Two", "Pen Name", "vi", "author-list-owner-0002");
      LibraryRepository.WorkRecord third =
          libraryService.createPrivateAuthoredWork(
              ownerId, "List Novel Three", "Pen Name", "vi", "author-list-owner-0003");
      LibraryRepository.WorkRecord imported =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Imported Must Be Hidden", "Pen Name", "vi", "author-list-owner-0004");
      LibraryRepository.WorkRecord publicWork =
          libraryService.createPrivateAuthoredWork(
              ownerId, "Public Must Be Hidden", "Pen Name", "vi", "author-list-owner-0005");
      LibraryRepository.WorkRecord foreign =
          libraryService.createPrivateAuthoredWork(
              otherOwnerId,
              "Foreign Must Be Hidden",
              "Other Pen Name",
              "vi",
              "author-list-foreign-0001");
      foreignWorkId = foreign.id();
      createdWorkIds.addAll(
          List.of(first.id(), second.id(), third.id(), imported.id(), publicWork.id()));
      libraryService.createPrivateAuthoredChapter(
          ownerId, third.id(), "A private chapter", "author-list-chapter-owner-01");
      setWorkSourceKind(imported.id(), "imported");
      setWorkVisibility(publicWork.id(), "public");

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherOwnerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      String route = "/api/v1/authoring/manuscripts";

      MvcResult policyBlocked =
          mockMvc.perform(get(route).cookie(ownerCookie).param("limit", "2")).andReturn();
      assertEquals(403, policyBlocked.getResponse().getStatus());
      assertTrue(
          policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(otherOwnerId);

      MvcResult firstPage =
          mockMvc.perform(get(route).cookie(ownerCookie).param("limit", "2")).andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(2, firstBody.path("items").size());
      assertEquals("private", firstBody.path("items").get(0).path("visibility").asText());
      assertEquals("draft", firstBody.path("items").get(0).path("publicationState").asText());
      assertFalse(firstPage.getResponse().getContentAsString().contains("ownerUserId"));
      assertFalse(firstPage.getResponse().getContentAsString().contains("sourceHash"));
      assertFalse(firstPage.getResponse().getContentAsString().contains("normalizedContent"));
      String nextCursor = firstBody.path("nextCursor").asText();
      assertFalse(nextCursor.isBlank());

      MvcResult secondPage =
          mockMvc
              .perform(
                  get(route).cookie(ownerCookie).param("limit", "2").param("cursor", nextCursor))
              .andReturn();
      assertEquals(200, secondPage.getResponse().getStatus());
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(1, secondBody.path("items").size());
      assertTrue(secondBody.path("nextCursor").isNull());

      List<String> listedIds = new ArrayList<>();
      firstBody.path("items").forEach(item -> listedIds.add(item.path("id").asText()));
      secondBody.path("items").forEach(item -> listedIds.add(item.path("id").asText()));
      assertEquals(3, listedIds.stream().distinct().count());
      assertTrue(
          listedIds.containsAll(
              List.of(first.id().toString(), second.id().toString(), third.id().toString())));
      assertFalse(listedIds.contains(imported.id().toString()));
      assertFalse(listedIds.contains(publicWork.id().toString()));
      assertFalse(listedIds.contains(foreign.id().toString()));
      JsonNode chapterCountItem =
          java.util.stream.Stream.concat(
                  java.util.stream.StreamSupport.stream(
                      firstBody.path("items").spliterator(), false),
                  java.util.stream.StreamSupport.stream(
                      secondBody.path("items").spliterator(), false))
              .filter(item -> third.id().toString().equals(item.path("id").asText()))
              .findFirst()
              .orElseThrow();
      assertEquals(1, chapterCountItem.path("chapterCount").asInt());

      assertEquals(
          400,
          mockMvc
              .perform(get(route).cookie(otherCookie).param("cursor", nextCursor))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          400,
          mockMvc
              .perform(get(route).cookie(ownerCookie).param("cursor", nextCursor + "="))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          400,
          mockMvc
              .perform(get(route).cookie(ownerCookie).param("limit", "101"))
              .andReturn()
              .getResponse()
              .getStatus());
    } finally {
      for (UUID workId : createdWorkIds) deleteBareAuthoredWorkData(ownerId, workId);
      deleteOwnerLibraries(ownerId);
      deleteSessionTestUser(ownerId);
      if (foreignWorkId != null) deleteLibraryUserData(otherOwnerId, foreignWorkId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void privateLibraryCoreScopesOwnersDefaultsPrivateAndRejectsDuplicateHashesAndOrder()
      throws Exception {
    UUID ownerId = createSessionTestUser("Library Owner");
    UUID otherOwnerId = createSessionTestUser("Other Library Owner");
    try {
      LibraryRepository.LibraryRecord defaultLibrary =
          libraryService.createLibrary(ownerId, "My Library");
      LibraryRepository.LibraryRecord secondLibrary =
          libraryService.createLibrary(ownerId, "Research Shelf");
      LibraryRepository.LibraryRecord otherLibrary =
          libraryService.createLibrary(otherOwnerId, "My Library");

      assertTrue(defaultLibrary.isDefault());
      assertFalse(secondLibrary.isDefault());
      assertTrue(otherLibrary.isDefault());
      assertEquals("private", defaultLibrary.visibility());

      byte[] digest = sha256("same-import-content");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId, defaultLibrary.id(), "A Private Novel", "Author", "vi", digest);
      assertEquals("private", work.visibility());
      assertEquals("active", work.status());
      assertEquals(0, work.chapterCount());
      assertEquals(1, libraryService.listWorks(ownerId, defaultLibrary.id(), "", 25).size());
      assertTrue(libraryService.findOwnedWork(otherOwnerId, work.id()).isEmpty());
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () -> libraryService.listWorks(otherOwnerId, defaultLibrary.id(), "", 25));
      assertThrows(
          LibraryRepository.DuplicateImportedWorkException.class,
          () ->
              libraryService.createPrivateImportedWork(
                  ownerId, secondLibrary.id(), "Duplicate Copy", "Author", "vi", digest));

      LibraryRepository.WorkRecord sameContentForOtherOwner =
          libraryService.createPrivateImportedWork(
              otherOwnerId, otherLibrary.id(), "Own Copy", "Author", "vi", digest);
      assertEquals("private", sameContentForOtherOwner.visibility());

      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, work.id(), 1, "Volume 1");
      SQLException duplicateVolumeOrder =
          assertThrows(
              SQLException.class,
              () -> insertTestVolume(UUID.randomUUID(), work.id(), 1, "Duplicate volume"));
      assertEquals("23505", duplicateVolumeOrder.getSQLState());

      LibraryRepository.ChapterRecord chapter =
          libraryService.createChapter(ownerId, work.id(), volume.id(), 1, "Chapter 1", "zh-Hans");
      assertEquals("draft", chapter.status());
      assertTrue(libraryService.findOwnedChapter(otherOwnerId, chapter.id()).isEmpty());
      assertThrows(
          LibraryRepository.WorkNotFoundException.class,
          () ->
              libraryService.createChapter(
                  otherOwnerId, work.id(), volume.id(), 2, "Foreign", "zh"));
      SQLException duplicateChapterOrder =
          assertThrows(
              SQLException.class,
              () ->
                  insertTestChapter(
                      UUID.randomUUID(), work.id(), volume.id(), 1, "Duplicate chapter"));
      assertEquals("23505", duplicateChapterOrder.getSQLState());

      assertThrows(
          IllegalArgumentException.class,
          () ->
              libraryService.appendChapterRevision(
                  ownerId,
                  chapter.id(),
                  "plain_text",
                  "Duplicate anchors must fail before persistence.",
                  List.of(
                      new LibraryService.NewSegment(1, "paragraph", "First", "same-anchor"),
                      new LibraryService.NewSegment(2, "paragraph", "Second", "same-anchor"))));
      assertEquals(0, revisionCount(chapter.id()));

      LibraryRepository.RevisionRecord revision =
          libraryService.appendChapterRevision(
              ownerId,
              chapter.id(),
              "plain_text",
              "First paragraph.\nSecond paragraph.",
              List.of(
                  new LibraryService.NewSegment(1, "paragraph", "First paragraph.", "p1"),
                  new LibraryService.NewSegment(2, "paragraph", "Second paragraph.", "p2")));
      assertEquals(1, revision.revisionNo());
      LibraryRepository.ChapterRecord readyChapter =
          libraryService.findOwnedChapter(ownerId, chapter.id()).orElseThrow();
      assertEquals("ready", readyChapter.status());
      assertEquals(revision.id(), readyChapter.sourceRevisionId());
      assertEquals(2, segmentCount(revision.id()));
      assertThrows(
          LibraryRepository.DuplicateRevisionException.class,
          () ->
              libraryService.appendChapterRevision(
                  ownerId,
                  chapter.id(),
                  "plain_text",
                  "First paragraph.\nSecond paragraph.",
                  List.of()));

      LibraryRepository.AssetRecord asset =
          libraryService.registerAsset(
              ownerId,
              defaultLibrary.id(),
              "private/" + UUID.randomUUID(),
              sha256("cover-image"),
              "image/webp",
              2048,
              "cover.webp");
      assertEquals("staged", asset.status());
      assertThrows(
          LibraryRepository.DuplicateAssetException.class,
          () ->
              libraryService.registerAsset(
                  ownerId,
                  secondLibrary.id(),
                  "private/" + UUID.randomUUID(),
                  sha256("cover-image"),
                  "image/webp",
                  2048,
                  "duplicate-cover.webp"));
      LibraryRepository.AssetRecord sameAssetForOtherOwner =
          libraryService.registerAsset(
              otherOwnerId,
              otherLibrary.id(),
              "private/" + UUID.randomUUID(),
              sha256("cover-image"),
              "image/webp",
              2048,
              "cover.webp");
      assertNotEquals(asset.ownerUserId(), sameAssetForOtherOwner.ownerUserId());
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryTestData(otherOwnerId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void readerProgressPersistsStableAnchorsAndReconcilesConcurrentSaves() throws Exception {
    UUID ownerId = createSessionTestUser("Progress Owner");
    UUID outsiderId = createSessionTestUser("Progress Outsider");
    UUID workId = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "Progress Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "Progress Novel",
              "Synthetic Author",
              "vi",
              sha256("progress-reader-work"));
      workId = work.id();
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, workId, 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 2, "Chapter 2", "vi");
      LibraryRepository.RevisionRecord oldRevision =
          libraryService.appendChapterRevision(
              ownerId,
              firstChapter.id(),
              "plain_text",
              "Old passage retained for anchor lookup.",
              List.of(
                  new LibraryService.NewSegment(
                      1, "paragraph", "Old passage retained for anchor lookup.", "legacy-anchor")));
      libraryService.appendChapterRevision(
          ownerId,
          firstChapter.id(),
          "plain_text",
          "Replacement passage with a different anchor.",
          List.of(
              new LibraryService.NewSegment(
                  1,
                  "paragraph",
                  "Replacement passage with a different anchor.",
                  "current-anchor")));
      libraryService.appendChapterRevision(
          ownerId,
          secondChapter.id(),
          "plain_text",
          "A second chapter for a second device.",
          List.of(
              new LibraryService.NewSegment(
                  1,
                  "paragraph",
                  "A second chapter for a second device.",
                  "second-device-anchor")));
      assertNotNull(oldRevision);
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession firstDevice = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie firstCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", firstDevice.cookieValue());
      String firstCsrf = csrfTokenService.tokenFor(firstDevice.cookieValue());
      SessionService.IssuedSession secondDevice = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie secondCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", secondDevice.cookieValue());
      String secondCsrf = csrfTokenService.tokenFor(secondDevice.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());

      MvcResult initial =
          mockMvc
              .perform(get("/api/v1/works/{workId}/progress", workId).cookie(firstCookie))
              .andReturn();
      assertEquals(200, initial.getResponse().getStatus());
      assertEquals("\"progress-0\"", initial.getResponse().getHeader("ETag"));
      assertTrue(initial.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode initialProgress = objectMapper.readTree(initial.getResponse().getContentAsString());
      assertEquals(0, initialProgress.path("version").asInt());
      assertTrue(initialProgress.path("chapterId").isNull());

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"expectedVersion\":0,\"chapterId\":\""
                              + firstChapter.id()
                              + "\",\"segmentAnchor\":\"legacy-anchor\",\"scrollRatio\":0.45}")
                      .cookie(firstCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      String firstPosition =
          "{\"expectedVersion\":0,\"chapterId\":\""
              + firstChapter.id()
              + "\",\"segmentAnchor\":\"legacy-anchor\",\"scrollRatio\":0.45}";
      MvcResult firstSave =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstPosition)
                      .header("X-CSRF-Token", firstCsrf)
                      .cookie(firstCookie))
              .andReturn();
      assertEquals(200, firstSave.getResponse().getStatus());
      assertEquals("\"progress-1\"", firstSave.getResponse().getHeader("ETag"));
      JsonNode savedPosition = objectMapper.readTree(firstSave.getResponse().getContentAsString());
      assertEquals(1, savedPosition.path("version").asInt());
      String savedAt = savedPosition.path("updatedAt").asText();

      MvcResult retry =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstPosition)
                      .header("X-CSRF-Token", firstCsrf)
                      .cookie(firstCookie))
              .andReturn();
      JsonNode retriedPosition = objectMapper.readTree(retry.getResponse().getContentAsString());
      assertEquals(200, retry.getResponse().getStatus());
      assertEquals(1, retriedPosition.path("version").asInt());
      assertEquals(savedAt, retriedPosition.path("updatedAt").asText());

      MvcResult rapidScroll =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"expectedVersion\":1,\"chapterId\":\""
                              + firstChapter.id()
                              + "\",\"segmentAnchor\":\"legacy-anchor\",\"scrollRatio\":0.62}")
                      .header("X-CSRF-Token", firstCsrf)
                      .cookie(firstCookie))
              .andReturn();
      assertEquals(200, rapidScroll.getResponse().getStatus());
      assertEquals(
          2,
          objectMapper
              .readTree(rapidScroll.getResponse().getContentAsString())
              .path("version")
              .asInt());

      MvcResult secondDeviceSave =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"expectedVersion\":2,\"chapterId\":\""
                              + secondChapter.id()
                              + "\",\"segmentAnchor\":\"second-device-anchor\",\"scrollRatio\":0.1}")
                      .header("X-CSRF-Token", secondCsrf)
                      .cookie(secondCookie))
              .andReturn();
      assertEquals(200, secondDeviceSave.getResponse().getStatus());
      assertEquals("\"progress-3\"", secondDeviceSave.getResponse().getHeader("ETag"));

      MvcResult staleDeviceSave =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstPosition)
                      .header("X-CSRF-Token", firstCsrf)
                      .cookie(firstCookie))
              .andReturn();
      JsonNode conflict = objectMapper.readTree(staleDeviceSave.getResponse().getContentAsString());
      assertEquals(409, staleDeviceSave.getResponse().getStatus());
      assertEquals("READER_PROGRESS_VERSION_STALE", conflict.path("code").asText());
      assertEquals(3, conflict.path("latestProgress").path("version").asInt());
      assertEquals(
          secondChapter.id().toString(),
          conflict.path("latestProgress").path("chapterId").asText());

      MvcResult reloaded =
          mockMvc
              .perform(get("/api/v1/works/{workId}/progress", workId).cookie(secondCookie))
              .andReturn();
      assertEquals(200, reloaded.getResponse().getStatus());
      JsonNode afterReload = objectMapper.readTree(reloaded.getResponse().getContentAsString());
      assertEquals(3, afterReload.path("version").asInt());
      assertEquals(secondChapter.id().toString(), afterReload.path("chapterId").asText());
      assertEquals("second-device-anchor", afterReload.path("segmentAnchor").asText());

      MvcResult invalidAnchor =
          mockMvc
              .perform(
                  put("/api/v1/works/{workId}/progress", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"expectedVersion\":3,\"chapterId\":\""
                              + firstChapter.id()
                              + "\",\"segmentAnchor\":\"not-an-anchor\",\"scrollRatio\":0.5}")
                      .header("X-CSRF-Token", secondCsrf)
                      .cookie(secondCookie))
              .andReturn();
      assertEquals(422, invalidAnchor.getResponse().getStatus());

      MvcResult outsider =
          mockMvc
              .perform(get("/api/v1/works/{workId}/progress", workId).cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsider.getResponse().getStatus());
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryUserData(ownerId, workId);
      deleteLibraryUserData(outsiderId, null);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void readerPreferencesArePrivateValidatedAndDurable() throws Exception {
    UUID ownerId = createSessionTestUser("Reader Preferences Owner");
    UUID otherId = createSessionTestUser("Reader Preferences Other");
    UUID pendingPolicyId = createSessionTestUser("Reader Preferences Pending Policy");
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(otherId);
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      SessionService.IssuedSession otherSession = sessionService.create(otherId).orElseThrow();
      var otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());
      SessionService.IssuedSession pendingPolicySession =
          sessionService.create(pendingPolicyId).orElseThrow();
      var pendingPolicyCookie =
          new jakarta.servlet.http.Cookie(
              "__Host-novel_session", pendingPolicySession.cookieValue());

      MvcResult unauthenticated = mockMvc.perform(get("/api/v1/me/reader-preferences")).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult defaults =
          mockMvc.perform(get("/api/v1/me/reader-preferences").cookie(ownerCookie)).andReturn();
      assertEquals(200, defaults.getResponse().getStatus());
      assertTrue(defaults.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode defaultPreferences =
          objectMapper.readTree(defaults.getResponse().getContentAsString());
      assertEquals(1, defaultPreferences.path("schemaVersion").asInt());
      assertEquals("light", defaultPreferences.path("theme").asText());
      assertEquals("serif", defaultPreferences.path("fontFamily").asText());
      assertEquals(20, defaultPreferences.path("fontSize").asInt());
      assertEquals(1.8, defaultPreferences.path("lineHeight").asDouble());
      assertEquals(720, defaultPreferences.path("contentWidth").asInt());

      String requested =
          "{\"schemaVersion\":1,\"theme\":\"sepia\",\"fontFamily\":\"sans\","
              + "\"fontSize\":24,\"lineHeight\":1.9,\"contentWidth\":840}";
      MvcResult missingCsrf =
          mockMvc
              .perform(
                  put("/api/v1/me/reader-preferences")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requested)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult saved =
          mockMvc
              .perform(
                  put("/api/v1/me/reader-preferences")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requested)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, saved.getResponse().getStatus());
      JsonNode savedPreferences = objectMapper.readTree(saved.getResponse().getContentAsString());
      assertEquals("sepia", savedPreferences.path("theme").asText());
      assertEquals("sans", savedPreferences.path("fontFamily").asText());
      assertEquals(24, savedPreferences.path("fontSize").asInt());
      assertEquals(1.9, savedPreferences.path("lineHeight").asDouble());
      assertEquals(840, savedPreferences.path("contentWidth").asInt());

      MvcResult invalid =
          mockMvc
              .perform(
                  put("/api/v1/me/reader-preferences")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"schemaVersion\":1,\"theme\":\"sepia\",\"fontFamily\":\"sans\","
                              + "\"fontSize\":99,\"lineHeight\":1.9,\"contentWidth\":840}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, invalid.getResponse().getStatus());

      MvcResult unknownField =
          mockMvc
              .perform(
                  put("/api/v1/me/reader-preferences")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"schemaVersion\":1,\"theme\":\"sepia\",\"fontFamily\":\"sans\","
                              + "\"fontSize\":24,\"lineHeight\":1.9,\"contentWidth\":840,"
                              + "\"trackingId\":\"must-be-rejected\"}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, unknownField.getResponse().getStatus());

      MvcResult ownerReload =
          mockMvc.perform(get("/api/v1/me/reader-preferences").cookie(ownerCookie)).andReturn();
      assertEquals(
          savedPreferences, objectMapper.readTree(ownerReload.getResponse().getContentAsString()));
      MvcResult otherUser =
          mockMvc.perform(get("/api/v1/me/reader-preferences").cookie(otherCookie)).andReturn();
      JsonNode otherPreferences =
          objectMapper.readTree(otherUser.getResponse().getContentAsString());
      assertEquals(200, otherUser.getResponse().getStatus());
      assertEquals("light", otherPreferences.path("theme").asText());
      assertEquals(20, otherPreferences.path("fontSize").asInt());
      MvcResult pendingPolicy =
          mockMvc
              .perform(get("/api/v1/me/reader-preferences").cookie(pendingPolicyCookie))
              .andReturn();
      assertEquals(403, pendingPolicy.getResponse().getStatus());
      assertTrue(
          pendingPolicy.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    } finally {
      deleteSessionTestUser(pendingPolicyId);
      deleteSessionTestUser(otherId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void readerBookmarksArePrivateStableIdempotentAndCursorPaged() throws Exception {
    UUID ownerId = createSessionTestUser("Reader Bookmark Owner");
    UUID outsiderId = createSessionTestUser("Reader Bookmark Outsider");
    UUID pendingPolicyId = createSessionTestUser("Reader Bookmark Pending Policy");
    UUID workId = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "Bookmark Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "Bookmark Story",
              "Synthetic Author",
              "vi",
              sha256("reader-bookmark-work"));
      workId = work.id();
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, workId, 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 2, "Chapter 2", "vi");
      libraryService.appendChapterRevision(
          ownerId,
          firstChapter.id(),
          "plain_text",
          "The old passage is retained.",
          List.of(
              new LibraryService.NewSegment(
                  1, "paragraph", "The old passage is retained.", "retained-anchor")));
      libraryService.appendChapterRevision(
          ownerId,
          firstChapter.id(),
          "plain_text",
          "The new passage is readable.",
          List.of(
              new LibraryService.NewSegment(
                  1, "paragraph", "The new passage is readable.", "current-anchor")));
      libraryService.appendChapterRevision(
          ownerId,
          secondChapter.id(),
          "plain_text",
          "A second chapter passage.",
          List.of(
              new LibraryService.NewSegment(
                  1, "paragraph", "A second chapter passage.", "second-anchor")));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      SessionService.IssuedSession pendingPolicySession =
          sessionService.create(pendingPolicyId).orElseThrow();
      var pendingPolicyCookie =
          new jakarta.servlet.http.Cookie(
              "__Host-novel_session", pendingPolicySession.cookieValue());

      MvcResult unauthenticated =
          mockMvc.perform(get("/api/v1/works/{workId}/bookmarks", workId)).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult defaults =
          mockMvc
              .perform(get("/api/v1/works/{workId}/bookmarks", workId).cookie(ownerCookie))
              .andReturn();
      assertEquals(200, defaults.getResponse().getStatus());
      assertTrue(defaults.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals(
          0,
          objectMapper.readTree(defaults.getResponse().getContentAsString()).path("items").size());

      MvcResult pendingPolicy =
          mockMvc
              .perform(get("/api/v1/works/{workId}/bookmarks", workId).cookie(pendingPolicyCookie))
              .andReturn();
      assertEquals(403, pendingPolicy.getResponse().getStatus());

      String firstRequest =
          "{\"chapterId\":\""
              + firstChapter.id()
              + "\",\"segmentAnchor\":\"retained-anchor\",\"label\":\"Chapter 1 · saved\"}";
      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0001")
                      .content(firstRequest)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0001")
                      .content(firstRequest)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode createdBookmark = objectMapper.readTree(created.getResponse().getContentAsString());
      String bookmarkId = createdBookmark.path("id").asText();
      assertEquals(workId.toString(), createdBookmark.path("workId").asText());
      assertEquals(firstChapter.id().toString(), createdBookmark.path("chapterId").asText());
      assertEquals("retained-anchor", createdBookmark.path("segmentAnchor").asText());

      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0001")
                      .content(firstRequest)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, retry.getResponse().getStatus());
      assertEquals(
          createdBookmark, objectMapper.readTree(retry.getResponse().getContentAsString()));
      assertEquals(1, bookmarkCount(ownerId, workId));

      MvcResult reusedKey =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0001")
                      .content(firstRequest.replace("saved", "edited"))
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, reusedKey.getResponse().getStatus());
      assertTrue(
          reusedKey.getResponse().getContentAsString().contains("BOOKMARK_IDEMPOTENCY_KEY_REUSED"));

      MvcResult samePosition =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0002")
                      .content(firstRequest.replace("owner-0001", "owner-0002"))
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, samePosition.getResponse().getStatus());
      assertEquals(
          bookmarkId,
          objectMapper
              .readTree(samePosition.getResponse().getContentAsString())
              .path("id")
              .asText());
      assertEquals(1, bookmarkCount(ownerId, workId));

      MvcResult invalidAnchor =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0003")
                      .content(
                          "{\"chapterId\":\""
                              + firstChapter.id()
                              + "\",\"segmentAnchor\":\"missing-anchor\",\"label\":\"Invalid\"}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, invalidAnchor.getResponse().getStatus());

      MvcResult unknownField =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0004")
                      .content(
                          firstRequest.substring(0, firstRequest.length() - 1) + ",\"debug\":true}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, unknownField.getResponse().getStatus());

      String secondRequest =
          "{\"chapterId\":\""
              + secondChapter.id()
              + "\",\"segmentAnchor\":\"second-anchor\",\"label\":\"Chapter 2 · saved\"}";
      MvcResult secondCreated =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/bookmarks", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Idempotency-Key", "bookmark-key-owner-0005")
                      .content(secondRequest)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, secondCreated.getResponse().getStatus());

      MvcResult firstPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/bookmarks", workId)
                      .param("limit", "1")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      JsonNode firstPageBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(1, firstPageBody.path("items").size());
      String cursor = firstPageBody.path("nextCursor").asText();
      assertFalse(cursor.isBlank());

      MvcResult secondPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/bookmarks", workId)
                      .param("limit", "1")
                      .param("cursor", cursor)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, secondPage.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(secondPage.getResponse().getContentAsString())
              .path("items")
              .size());

      MvcResult tamperedCursor =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/bookmarks", workId)
                      .param("cursor", tamperCursor(cursor))
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(400, tamperedCursor.getResponse().getStatus());

      MvcResult outsiderList =
          mockMvc
              .perform(get("/api/v1/works/{workId}/bookmarks", workId).cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderList.getResponse().getStatus());
      MvcResult outsiderDelete =
          mockMvc
              .perform(
                  delete("/api/v1/bookmarks/{bookmarkId}", bookmarkId)
                      .header(
                          "X-CSRF-Token", csrfTokenService.tokenFor(outsiderSession.cookieValue()))
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderDelete.getResponse().getStatus());

      MvcResult ownerDelete =
          mockMvc
              .perform(
                  delete("/api/v1/bookmarks/{bookmarkId}", bookmarkId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, ownerDelete.getResponse().getStatus());
      assertTrue(ownerDelete.getResponse().getHeader("Cache-Control").contains("no-store"));
      MvcResult missingAfterDelete =
          mockMvc
              .perform(
                  delete("/api/v1/bookmarks/{bookmarkId}", bookmarkId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, missingAfterDelete.getResponse().getStatus());
    } finally {
      deleteLibraryUserData(ownerId, workId);
      deleteSessionTestUser(pendingPolicyId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void readerAnnotationsArePrivateUnicodeBoundRevisionAwareAndOptimisticallyUpdated()
      throws Exception {
    UUID ownerId = createSessionTestUser("Reader Annotation Owner");
    UUID outsiderId = createSessionTestUser("Reader Annotation Outsider");
    UUID pendingPolicyId = createSessionTestUser("Reader Annotation Pending Policy");
    UUID workId = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "Annotation Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "Annotation Story",
              "Synthetic Author",
              "vi",
              sha256("reader-annotation-work"));
      workId = work.id();
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, workId, 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(ownerId, workId, volume.id(), 2, "Chapter 2", "vi");
      String firstText = "Mây ☁️ mở cửa";
      String secondText = "Tầng 2 khép lại";
      LibraryRepository.RevisionRecord firstRevision =
          libraryService.appendChapterRevision(
              ownerId,
              firstChapter.id(),
              "plain_text",
              firstText + "\n" + secondText,
              List.of(
                  new LibraryService.NewSegment(1, "paragraph", firstText, "first-anchor"),
                  new LibraryService.NewSegment(2, "paragraph", secondText, "second-anchor")));
      LibraryRepository.RevisionRecord secondRevision =
          libraryService.appendChapterRevision(
              ownerId,
              secondChapter.id(),
              "plain_text",
              "Chương thứ hai có nắng.",
              List.of(
                  new LibraryService.NewSegment(
                      1, "paragraph", "Chương thứ hai có nắng.", "other-chapter-anchor")));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      SessionService.IssuedSession pendingSession =
          sessionService.create(pendingPolicyId).orElseThrow();
      var pendingCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", pendingSession.cookieValue());
      String pendingCsrf = csrfTokenService.tokenFor(pendingSession.cookieValue());

      assertEquals(
          401,
          mockMvc
              .perform(get("/api/v1/works/{workId}/annotations", workId))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          403,
          mockMvc
              .perform(get("/api/v1/works/{workId}/annotations", workId).cookie(pendingCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      MvcResult pendingDelete =
          mockMvc
              .perform(
                  delete("/api/v1/works/{workId}/annotations", workId)
                      .header("X-CSRF-Token", pendingCsrf)
                      .cookie(pendingCookie))
              .andReturn();
      assertEquals(204, pendingDelete.getResponse().getStatus());

      String crossSegmentQuote = "ây ☁️ mở cửaTầng";
      String firstRequest =
          objectMapper.writeValueAsString(
              Map.of(
                  "chapterId",
                  firstChapter.id(),
                  "sourceRevisionId",
                  firstRevision.id(),
                  "startAnchor",
                  "first-anchor",
                  "startOffset",
                  1,
                  "endAnchor",
                  "second-anchor",
                  "endOffset",
                  4,
                  "quoteSnapshot",
                  crossSegmentQuote,
                  "note",
                  "Ghi chú riêng tư",
                  "color",
                  "amber"));
      MvcResult missingCsrf =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstRequest)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult created =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstRequest)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, created.getResponse().getStatus());
      assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("\"annotation-1\"", created.getResponse().getHeader("ETag"));
      JsonNode firstAnnotation = objectMapper.readTree(created.getResponse().getContentAsString());
      String annotationId = firstAnnotation.path("id").asText();
      assertEquals(
          firstRevision.id().toString(), firstAnnotation.path("chapterRevisionId").asText());
      assertEquals(crossSegmentQuote, firstAnnotation.path("quoteSnapshot").asText());
      assertEquals("CURRENT", firstAnnotation.path("anchorState").asText());
      assertEquals("Ghi chú riêng tư", firstAnnotation.path("note").asText());
      assertFalse(firstAnnotation.has("userId"));
      assertFalse(
          created.getResponse().getContentAsString().contains(firstText + "\\n" + secondText));

      MvcResult duplicate =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstRequest.replace("Ghi chú riêng tư", "Không ghi đè"))
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, duplicate.getResponse().getStatus());
      assertEquals(
          "Ghi chú riêng tư",
          objectMapper
              .readTree(duplicate.getResponse().getContentAsString())
              .path("note")
              .asText());

      String secondRequest =
          objectMapper.writeValueAsString(
              Map.of(
                  "chapterId", firstChapter.id(),
                  "sourceRevisionId", firstRevision.id(),
                  "startAnchor", "second-anchor",
                  "startOffset", 0,
                  "endAnchor", "second-anchor",
                  "endOffset", 4,
                  "quoteSnapshot", "Tầng",
                  "note", "",
                  "color", "mint"));
      MvcResult secondCreated =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(secondRequest)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(201, secondCreated.getResponse().getStatus());

      MvcResult badQuote =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(firstRequest.replace(crossSegmentQuote, "Không phải nguyên tác"))
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(422, badQuote.getResponse().getStatus());

      MvcResult firstPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/annotations", workId)
                      .param("chapterId", firstChapter.id().toString())
                      .param("limit", "1")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstPageBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(1, firstPageBody.path("items").size());
      String cursor = firstPageBody.path("nextCursor").asText();
      assertFalse(cursor.isBlank());
      assertEquals(
          400,
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/annotations", workId)
                      .param("chapterId", secondChapter.id().toString())
                      .param("cursor", cursor)
                      .cookie(ownerCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          404,
          mockMvc
              .perform(get("/api/v1/works/{workId}/annotations", workId).cookie(outsiderCookie))
              .andReturn()
              .getResponse()
              .getStatus());

      MvcResult missingIfMatch =
          mockMvc
              .perform(
                  patch("/api/v1/annotations/{annotationId}", annotationId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"note\":\"Sửa mới\",\"color\":\"sky\"}")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(428, missingIfMatch.getResponse().getStatus());

      MvcResult updated =
          mockMvc
              .perform(
                  patch("/api/v1/annotations/{annotationId}", annotationId)
                      .header("If-Match", "\"annotation-1\"")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"note\":\"Sửa mới\",\"color\":\"sky\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, updated.getResponse().getStatus());
      assertEquals("\"annotation-2\"", updated.getResponse().getHeader("ETag"));
      MvcResult staleUpdate =
          mockMvc
              .perform(
                  patch("/api/v1/annotations/{annotationId}", annotationId)
                      .header("If-Match", "\"annotation-1\"")
                      .header("X-CSRF-Token", ownerCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"note\":\"Draft cũ\",\"color\":\"rose\"}")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, staleUpdate.getResponse().getStatus());
      assertEquals("\"annotation-2\"", staleUpdate.getResponse().getHeader("ETag"));
      assertTrue(
          objectMapper
              .readTree(staleUpdate.getResponse().getContentAsString())
              .path("latestAnnotation")
              .path("note")
              .asText()
              .equals("Sửa mới"));

      MvcResult staleRevision =
          mockMvc
              .perform(
                  post("/api/v1/works/{workId}/annotations", workId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          firstRequest.replace(
                              firstRevision.id().toString(), secondRevision.id().toString()))
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(409, staleRevision.getResponse().getStatus());

      libraryService.appendChapterRevision(
          ownerId,
          firstChapter.id(),
          "plain_text",
          "Một phiên bản nội dung mới.",
          List.of(
              new LibraryService.NewSegment(
                  1, "paragraph", "Một phiên bản nội dung mới.", "new-revision-anchor")));
      MvcResult staleList =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/annotations", workId)
                      .param("chapterId", firstChapter.id().toString())
                      .cookie(ownerCookie))
              .andReturn();
      JsonNode staleItems =
          objectMapper.readTree(staleList.getResponse().getContentAsString()).path("items");
      assertEquals(2, staleItems.size());
      assertTrue(staleItems.toString().contains("STALE"));

      MvcResult outsiderDelete =
          mockMvc
              .perform(
                  delete("/api/v1/annotations/{annotationId}", annotationId)
                      .header(
                          "X-CSRF-Token", csrfTokenService.tokenFor(outsiderSession.cookieValue()))
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderDelete.getResponse().getStatus());
      MvcResult deleteAll =
          mockMvc
              .perform(
                  delete("/api/v1/works/{workId}/annotations", workId)
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, deleteAll.getResponse().getStatus());
      assertEquals(0, annotationCount(ownerId, workId));
    } finally {
      deleteLibraryUserData(ownerId, workId);
      deleteSessionTestUser(pendingPolicyId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void readingHistoryIsPrivateReadableRecentCursorPagedAndDeletesOnlySavedProgress()
      throws Exception {
    UUID ownerId = createSessionTestUser("Reader History Owner");
    UUID outsiderId = createSessionTestUser("Reader History Outsider");
    UUID pendingPolicyId = createSessionTestUser("Reader History Pending Policy");
    List<ReaderHistoryFixture> fixtures = new ArrayList<>();
    try {
      ReaderHistoryFixture first =
          createReaderHistoryFixture(ownerId, "History First", "BODY_FIRST_SECRET");
      ReaderHistoryFixture second =
          createReaderHistoryFixture(ownerId, "History Second", "BODY_SECOND_SECRET");
      ReaderHistoryFixture expired =
          createReaderHistoryFixture(ownerId, "History Expired", "BODY_EXPIRED_SECRET");
      fixtures.addAll(List.of(first, second, expired));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      var ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      SessionService.IssuedSession pendingPolicySession =
          sessionService.create(pendingPolicyId).orElseThrow();
      var pendingPolicyCookie =
          new jakarta.servlet.http.Cookie(
              "__Host-novel_session", pendingPolicySession.cookieValue());

      assertEquals(
          401,
          mockMvc.perform(get("/api/v1/me/reading-history")).andReturn().getResponse().getStatus());
      MvcResult empty =
          mockMvc.perform(get("/api/v1/me/reading-history").cookie(ownerCookie)).andReturn();
      assertEquals(200, empty.getResponse().getStatus());
      assertTrue(empty.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals(
          0, objectMapper.readTree(empty.getResponse().getContentAsString()).path("items").size());
      assertEquals(
          403,
          mockMvc
              .perform(get("/api/v1/me/reading-history").cookie(pendingPolicyCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      MvcResult pendingPolicyDelete =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history")
                      .header(
                          "X-CSRF-Token",
                          csrfTokenService.tokenFor(pendingPolicySession.cookieValue()))
                      .cookie(pendingPolicyCookie))
              .andReturn();
      assertEquals(204, pendingPolicyDelete.getResponse().getStatus());

      for (ReaderHistoryFixture fixture : List.of(first, second, expired)) {
        MvcResult progress =
            mockMvc
                .perform(
                    put("/api/v1/works/{workId}/progress", fixture.workId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"expectedVersion\":0,\"chapterId\":\""
                                + fixture.chapterId()
                                + "\",\"segmentAnchor\":\"history-anchor\",\"scrollRatio\":0.37}")
                        .header("X-CSRF-Token", ownerCsrf)
                        .cookie(ownerCookie))
                .andReturn();
        assertEquals(200, progress.getResponse().getStatus());
      }
      Instant now = Instant.now();
      setReaderProgressTimestamp(ownerId, first.workId(), now.minusSeconds(2 * 24 * 60 * 60));
      setReaderProgressTimestamp(ownerId, second.workId(), now.minusSeconds(24 * 60 * 60));
      setReaderProgressTimestamp(ownerId, expired.workId(), now.minusSeconds(366L * 24 * 60 * 60));

      MvcResult firstPage =
          mockMvc
              .perform(get("/api/v1/me/reading-history").param("limit", "1").cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(1, firstBody.path("items").size());
      assertEquals(
          "History Second", firstBody.path("items").get(0).path("work").path("title").asText());
      assertEquals(
          "History Second Chapter",
          firstBody.path("items").get(0).path("chapter").path("title").asText());
      assertTrue(firstBody.path("items").get(0).path("progress").hasNonNull("updatedAt"));
      assertFalse(firstPage.getResponse().getContentAsString().contains("BODY_SECOND_SECRET"));
      assertFalse(firstPage.getResponse().getContentAsString().contains("ownerUserId"));
      String cursor = firstBody.path("nextCursor").asText();
      assertFalse(cursor.isBlank());

      MvcResult secondPage =
          mockMvc
              .perform(
                  get("/api/v1/me/reading-history")
                      .param("limit", "1")
                      .param("cursor", cursor)
                      .cookie(ownerCookie))
              .andReturn();
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(200, secondPage.getResponse().getStatus());
      assertEquals(
          "History First", secondBody.path("items").get(0).path("work").path("title").asText());
      assertTrue(secondBody.path("nextCursor").isNull());
      MvcResult cursorReplay =
          mockMvc
              .perform(
                  get("/api/v1/me/reading-history")
                      .param("limit", "1")
                      .param("cursor", cursor)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, cursorReplay.getResponse().getStatus());
      assertEquals(
          secondBody, objectMapper.readTree(cursorReplay.getResponse().getContentAsString()));

      MvcResult actorBoundCursor =
          mockMvc
              .perform(
                  get("/api/v1/me/reading-history").param("cursor", cursor).cookie(outsiderCookie))
              .andReturn();
      assertEquals(400, actorBoundCursor.getResponse().getStatus());
      MvcResult tamperedCursor =
          mockMvc
              .perform(
                  get("/api/v1/me/reading-history")
                      .param("cursor", tamperCursor(cursor))
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(400, tamperedCursor.getResponse().getStatus());
      MvcResult outsiderList =
          mockMvc.perform(get("/api/v1/me/reading-history").cookie(outsiderCookie)).andReturn();
      assertEquals(200, outsiderList.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(outsiderList.getResponse().getContentAsString())
              .path("items")
              .size());
      assertFalse(firstPage.getResponse().getContentAsString().contains("History Expired"));
      MvcResult expiredResumePosition =
          mockMvc
              .perform(get("/api/v1/works/{workId}/progress", expired.workId()).cookie(ownerCookie))
              .andReturn();
      assertEquals(200, expiredResumePosition.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(expiredResumePosition.getResponse().getContentAsString())
              .path("version")
              .asInt());

      deleteReaderHistoryWork(ownerId, first.workId());
      MvcResult afterWorkDeletion =
          mockMvc.perform(get("/api/v1/me/reading-history").cookie(ownerCookie)).andReturn();
      assertEquals(200, afterWorkDeletion.getResponse().getStatus());
      JsonNode afterDeletionBody =
          objectMapper.readTree(afterWorkDeletion.getResponse().getContentAsString());
      assertEquals(1, afterDeletionBody.path("items").size());
      assertEquals(
          "History Second",
          afterDeletionBody.path("items").get(0).path("work").path("title").asText());
      MvcResult deleteAfterWorkRemoval =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history/{workId}", first.workId())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, deleteAfterWorkRemoval.getResponse().getStatus());
      assertEquals(0, readerProgressCount(ownerId, first.workId()));

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history/{workId}", second.workId())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());
      MvcResult outsiderDelete =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history/{workId}", second.workId())
                      .header("X-CSRF-Token", outsiderCsrf)
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(204, outsiderDelete.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(
                  mockMvc
                      .perform(get("/api/v1/me/reading-history").cookie(ownerCookie))
                      .andReturn()
                      .getResponse()
                      .getContentAsString())
              .path("items")
              .size());

      MvcResult deleted =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history/{workId}", second.workId())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, deleted.getResponse().getStatus());
      assertTrue(deleted.getResponse().getHeader("Cache-Control").contains("no-store"));
      MvcResult idempotentDelete =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history/{workId}", second.workId())
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, idempotentDelete.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(
                  mockMvc
                      .perform(get("/api/v1/me/reading-history").cookie(ownerCookie))
                      .andReturn()
                      .getResponse()
                      .getContentAsString())
              .path("items")
              .size());

      assertEquals(
          403,
          mockMvc
              .perform(delete("/api/v1/me/reading-history").cookie(ownerCookie))
              .andReturn()
              .getResponse()
              .getStatus());
      MvcResult cleared =
          mockMvc
              .perform(
                  delete("/api/v1/me/reading-history")
                      .header("X-CSRF-Token", ownerCsrf)
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(204, cleared.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(
                  mockMvc
                      .perform(get("/api/v1/me/reading-history").cookie(ownerCookie))
                      .andReturn()
                      .getResponse()
                      .getContentAsString())
              .path("items")
              .size());
    } finally {
      deleteReaderHistoryFixtures(ownerId, fixtures);
      deleteSessionTestUser(pendingPolicyId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(ownerId);
    }
  }

  @Test
  void sourceOnlyReaderRechecksOwnerPaginatesAndNeverPutsBodyInManifest() throws Exception {
    UUID ownerId = createSessionTestUser("Reader API Owner");
    UUID outsiderId = createSessionTestUser("Reader API Outsider");
    UUID pendingPolicyId = createSessionTestUser("Reader API Pending Policy");
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "Reader Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              ownerId,
              library.id(),
              "Private Reader Story",
              "Synthetic Author",
              "vi",
              sha256("reader-api-work"));
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(ownerId, work.id(), 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(ownerId, work.id(), volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(ownerId, work.id(), volume.id(), 2, "Chapter 2", "vi");
      LibraryRepository.ChapterRecord draftChapter =
          libraryService.createChapter(ownerId, work.id(), volume.id(), 3, "Chapter 3", "vi");
      LibraryRepository.RevisionRecord firstRevision =
          libraryService.appendChapterRevision(
              ownerId,
              firstChapter.id(),
              "epub_xhtml",
              "Opening line.\n\nSecond paragraph.\n\nClosing line.",
              List.of(
                  new LibraryService.NewSegment(1, "heading", "Opening line.", "c1-s1"),
                  new LibraryService.NewSegment(2, "paragraph", "Second paragraph.", "c1-s2"),
                  new LibraryService.NewSegment(3, "quote", "Closing line.", "c1-s3")));
      libraryService.appendChapterRevision(
          ownerId,
          secondChapter.id(),
          "epub_xhtml",
          "Second chapter body.",
          List.of(new LibraryService.NewSegment(1, "paragraph", "Second chapter body.", "c2-s1")));
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(outsiderId);

      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      jakarta.servlet.http.Cookie outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      SessionService.IssuedSession pendingPolicySession =
          sessionService.create(pendingPolicyId).orElseThrow();
      jakarta.servlet.http.Cookie pendingPolicyCookie =
          new jakarta.servlet.http.Cookie(
              "__Host-novel_session", pendingPolicySession.cookieValue());

      MvcResult unauthenticated =
          mockMvc.perform(get("/api/v1/works/{workId}/reader-manifest", work.id())).andReturn();
      assertEquals(401, unauthenticated.getResponse().getStatus());

      MvcResult blockedByPolicy =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/reader-manifest", work.id())
                      .cookie(pendingPolicyCookie))
              .andReturn();
      assertEquals(403, blockedByPolicy.getResponse().getStatus());
      assertTrue(
          blockedByPolicy
              .getResponse()
              .getContentAsString()
              .contains("POLICY_ACCEPTANCE_REQUIRED"));

      MvcResult firstManifestPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/reader-manifest", work.id())
                      .param("limit", "1")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstManifestPage.getResponse().getStatus());
      assertTrue(firstManifestPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstManifest =
          objectMapper.readTree(firstManifestPage.getResponse().getContentAsString());
      assertEquals("Private Reader Story", firstManifest.path("work").path("title").asText());
      assertEquals(3, firstManifest.path("work").path("chapterCount").asInt());
      assertEquals(1, firstManifest.path("chapters").get(0).path("ordinal").asInt());
      assertEquals("readable", firstManifest.path("chapters").get(0).path("accessState").asText());
      assertTrue(firstManifest.hasNonNull("nextCursor"));
      assertFalse(
          firstManifestPage.getResponse().getContentAsString().contains("Second paragraph."));

      String manifestCursor = firstManifest.path("nextCursor").asText();
      MvcResult secondManifestPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/reader-manifest", work.id())
                      .param("limit", "1")
                      .param("cursor", manifestCursor)
                      .cookie(ownerCookie))
              .andReturn();
      JsonNode secondManifest =
          objectMapper.readTree(secondManifestPage.getResponse().getContentAsString());
      assertEquals(200, secondManifestPage.getResponse().getStatus());
      assertEquals(2, secondManifest.path("chapters").get(0).path("ordinal").asInt());
      assertEquals("readable", secondManifest.path("chapters").get(0).path("accessState").asText());
      assertTrue(secondManifest.hasNonNull("nextCursor"));

      MvcResult lastManifestPage =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/reader-manifest", work.id())
                      .param("limit", "1")
                      .param("cursor", secondManifest.path("nextCursor").asText())
                      .cookie(ownerCookie))
              .andReturn();
      JsonNode lastManifest =
          objectMapper.readTree(lastManifestPage.getResponse().getContentAsString());
      assertEquals(200, lastManifestPage.getResponse().getStatus());
      assertEquals(
          draftChapter.id().toString(), lastManifest.path("chapters").get(0).path("id").asText());
      assertEquals(
          "unavailable", lastManifest.path("chapters").get(0).path("accessState").asText());
      assertTrue(lastManifest.path("nextCursor").isNull());

      MvcResult firstContentPage =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .param("limit", "2")
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(200, firstContentPage.getResponse().getStatus());
      assertTrue(firstContentPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode content = objectMapper.readTree(firstContentPage.getResponse().getContentAsString());
      assertEquals(firstRevision.id().toString(), content.path("sourceRevisionId").asText());
      assertEquals("heading", content.path("segments").get(0).path("type").asText());
      assertEquals("Opening line.", content.path("segments").get(0).path("sourceText").asText());
      assertEquals(2, content.path("segments").size());
      assertTrue(content.hasNonNull("nextSegmentCursor"));

      MvcResult lastContentPage =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .param("limit", "2")
                      .param("revision", firstRevision.id().toString())
                      .param("cursor", content.path("nextSegmentCursor").asText())
                      .cookie(ownerCookie))
              .andReturn();
      JsonNode finalContent =
          objectMapper.readTree(lastContentPage.getResponse().getContentAsString());
      assertEquals(200, lastContentPage.getResponse().getStatus());
      assertEquals(1, finalContent.path("segments").size());
      assertEquals(
          "Closing line.", finalContent.path("segments").get(0).path("sourceText").asText());
      assertTrue(finalContent.path("nextSegmentCursor").isNull());

      MvcResult mismatchedCursor =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", secondChapter.id())
                      .param("cursor", content.path("nextSegmentCursor").asText())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(400, mismatchedCursor.getResponse().getStatus());
      assertTrue(
          mismatchedCursor.getResponse().getContentAsString().contains("INVALID_READER_CURSOR"));

      MvcResult outsiderManifest =
          mockMvc
              .perform(
                  get("/api/v1/works/{workId}/reader-manifest", work.id()).cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderManifest.getResponse().getStatus());
      MvcResult outsiderContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .cookie(outsiderCookie))
              .andReturn();
      assertEquals(404, outsiderContent.getResponse().getStatus());
      assertFalse(outsiderContent.getResponse().getContentAsString().contains("Second paragraph."));

      MvcResult draftContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", draftChapter.id())
                      .cookie(ownerCookie))
              .andReturn();
      assertEquals(404, draftContent.getResponse().getStatus());
      assertFalse(draftContent.getResponse().getContentAsString().contains("chapter body"));
    } finally {
      deleteLibraryTestData(ownerId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(outsiderId);
      deleteSessionTestUser(pendingPolicyId);
    }
  }

  @Test
  void privateLibraryCursorsAreStableOwnerAndFilterBoundAndSearchTagsAndStatus() throws Exception {
    UUID ownerId = createSessionTestUser("Cursor Library Owner");
    UUID otherOwnerId = createSessionTestUser("Other Cursor Owner");
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(ownerId, "Cursor Shelf");
      LibraryRepository.LibraryRecord secondLibrary =
          libraryService.createLibrary(ownerId, "Second Shelf");
      LibraryRepository.LibraryRecord otherLibrary =
          libraryService.createLibrary(otherOwnerId, "Other Shelf");
      LibraryRepository.WorkRecord dragonRiver =
          libraryService.createPrivateImportedWork(
              ownerId, library.id(), "Dragon River", "Writer A", "zh", sha256("cursor-work-a"));
      LibraryRepository.WorkRecord dragonGate =
          libraryService.createPrivateImportedWork(
              ownerId, library.id(), "Dragon Gate", "Writer B", "zh", sha256("cursor-work-b"));
      LibraryRepository.WorkRecord moonCity =
          libraryService.createPrivateImportedWork(
              ownerId, library.id(), "Moon City", "Dragon Pen", "zh", sha256("cursor-work-c"));
      setWorkCreatedAt(dragonRiver.id(), Instant.parse("2026-01-04T00:00:00Z"));
      setWorkCreatedAt(dragonGate.id(), Instant.parse("2026-01-03T00:00:00Z"));
      setWorkCreatedAt(moonCity.id(), Instant.parse("2026-01-02T00:00:00Z"));
      setWorkStatus(dragonGate.id(), "archived");

      libraryService.addTagToWork(ownerId, dragonRiver.id(), "Tiên Hiệp");
      libraryService.addTagToWork(ownerId, dragonGate.id(), "TIÊN-hiệp");
      libraryService.addTagToWork(ownerId, moonCity.id(), "tiên-hiệp");
      assertThrows(
          LibraryRepository.WorkNotFoundException.class,
          () -> libraryService.addTagToWork(otherOwnerId, dragonRiver.id(), "tiên-hiệp"));

      LibraryService.Page<LibraryRepository.LibraryRecord> firstLibraryPage =
          libraryService.listLibraries(ownerId, null, 1);
      assertEquals(1, firstLibraryPage.items().size());
      assertTrue(firstLibraryPage.items().getFirst().isDefault());
      assertEquals(
          1,
          libraryService.listLibraries(ownerId, firstLibraryPage.nextCursor(), 1).items().size());
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () -> libraryService.listLibraries(otherOwnerId, firstLibraryPage.nextCursor(), 1));

      LibraryService.Page<LibraryRepository.WorkRecord> firstWorkPage =
          libraryService.listWorks(ownerId, library.id(), "dragon", "tiên hiệp", "active", null, 1);
      assertEquals(1, firstWorkPage.items().size());
      assertTrue(firstWorkPage.nextCursor() != null && !firstWorkPage.nextCursor().isBlank());
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  ownerId,
                  library.id(),
                  "moon",
                  "tiên-hiệp",
                  "active",
                  firstWorkPage.nextCursor(),
                  1));
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  ownerId,
                  secondLibrary.id(),
                  "dragon",
                  "tiên-hiệp",
                  "active",
                  firstWorkPage.nextCursor(),
                  1));

      LibraryRepository.WorkRecord insertedAfterFirstPage =
          libraryService.createPrivateImportedWork(
              ownerId, library.id(), "Dragon Update", "Writer C", "zh", sha256("cursor-work-d"));
      libraryService.addTagToWork(ownerId, insertedAfterFirstPage.id(), "tiên-hiệp");
      setWorkCreatedAt(insertedAfterFirstPage.id(), Instant.parse("2026-01-05T00:00:00Z"));

      LibraryService.Page<LibraryRepository.WorkRecord> secondWorkPage =
          libraryService.listWorks(
              ownerId,
              library.id(),
              "dragon",
              "tiên-hiệp",
              "active",
              firstWorkPage.nextCursor(),
              1);
      assertEquals(1, secondWorkPage.items().size());
      assertNotEquals(
          firstWorkPage.items().getFirst().id(), secondWorkPage.items().getFirst().id());
      assertNotEquals(insertedAfterFirstPage.id(), secondWorkPage.items().getFirst().id());
      assertNull(secondWorkPage.nextCursor());

      LibraryService.Page<LibraryRepository.WorkRecord> archived =
          libraryService.listWorks(
              ownerId, library.id(), "dragon", "tiên-hiệp", "archived", null, 10);
      assertEquals(
          List.of(dragonGate.id()),
          archived.items().stream().map(LibraryRepository.WorkRecord::id).toList());
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.listWorks(
                  ownerId, otherLibrary.id(), "dragon", "tiên-hiệp", "active", null, 10));
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryTestData(otherOwnerId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(otherOwnerId);
    }
  }

  @Test
  void workspaceLifecycleKeepsOneOwnerAndVersionsMembershipChanges() throws Exception {
    UUID ownerId = createSessionTestUser("Workspace Owner");
    UUID adminId = createSessionTestUser("Workspace Admin");
    UUID editorId = createSessionTestUser("Workspace Editor");
    UUID readerId = createSessionTestUser("Workspace Reader");
    UUID outsiderId = createSessionTestUser("Workspace Outsider");
    UUID workspaceId = null;
    try {
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "  Shared Reading Room  ");
      UUID createdWorkspaceId = workspace.id();
      workspaceId = createdWorkspaceId;
      assertEquals("Shared Reading Room", workspace.name());
      assertEquals(1, workspace.scopeVersion());
      assertEquals(
          WorkspaceRepository.Role.OWNER,
          workspaceService.listMembers(ownerId, workspaceId).getFirst().role());
      SQLException missingActiveOwner =
          assertThrows(
              SQLException.class,
              () -> {
                try (Connection connection = dataSource.getConnection()) {
                  connection.setAutoCommit(false);
                  try (PreparedStatement deleteOwner =
                      connection.prepareStatement(
                          """
                            DELETE FROM workspace_members
                            WHERE workspace_id = ? AND user_id = ? AND role = 'owner'
                            """)) {
                    deleteOwner.setObject(1, createdWorkspaceId);
                    deleteOwner.setObject(2, ownerId);
                    assertEquals(1, deleteOwner.executeUpdate());
                  }
                  connection.commit();
                }
              });
      assertEquals("23514", missingActiveOwner.getSQLState());

      WorkspaceRepository.MemberRecord admin =
          workspaceService.addMember(ownerId, workspaceId, adminId, WorkspaceRepository.Role.ADMIN);
      assertEquals(WorkspaceRepository.MemberStatus.ACTIVE, admin.status());
      workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
      assertEquals(
          4, workspaceService.findWorkspace(ownerId, workspaceId).orElseThrow().scopeVersion());

      assertThrows(
          WorkspaceRepository.WorkspaceAccessDeniedException.class,
          () ->
              workspaceService.addMember(
                  outsiderId, createdWorkspaceId, outsiderId, WorkspaceRepository.Role.READER));
      assertThrows(
          WorkspaceRepository.WorkspaceAccessDeniedException.class,
          () ->
              workspaceService.addMember(
                  readerId, createdWorkspaceId, outsiderId, WorkspaceRepository.Role.READER));
      assertThrows(
          WorkspaceRepository.OwnershipTransferRequiredException.class,
          () ->
              workspaceService.addMember(
                  ownerId, createdWorkspaceId, outsiderId, WorkspaceRepository.Role.OWNER));
      assertThrows(
          WorkspaceRepository.WorkspaceAccessDeniedException.class,
          () -> workspaceService.transferOwnership(adminId, createdWorkspaceId, editorId));
      assertThrows(
          WorkspaceRepository.WorkspaceMemberConflictException.class,
          () ->
              workspaceService.addMember(
                  ownerId, createdWorkspaceId, adminId, WorkspaceRepository.Role.EDITOR));

      WorkspaceRepository.MemberRecord changed =
          workspaceService.changeMemberRole(
              adminId, workspaceId, readerId, WorkspaceRepository.Role.EDITOR);
      assertEquals(WorkspaceRepository.Role.EDITOR, changed.role());
      assertEquals(
          5, workspaceService.findWorkspace(ownerId, workspaceId).orElseThrow().scopeVersion());

      WorkspaceRepository.WorkspaceRecord transferred =
          workspaceService.transferOwnership(ownerId, workspaceId, adminId);
      assertEquals(6, transferred.scopeVersion());
      assertEquals(
          WorkspaceRepository.Role.ADMIN,
          workspaceService.listMembers(ownerId, workspaceId).stream()
              .filter(member -> member.userId().equals(ownerId))
              .findFirst()
              .orElseThrow()
              .role());
      assertEquals(
          WorkspaceRepository.Role.OWNER,
          workspaceService.listMembers(adminId, workspaceId).stream()
              .filter(member -> member.userId().equals(adminId))
              .findFirst()
              .orElseThrow()
              .role());
      assertThrows(
          WorkspaceRepository.WorkspaceOwnerCannotBeRevokedException.class,
          () -> workspaceService.revokeMember(adminId, createdWorkspaceId, adminId));

      WorkspaceRepository.MemberRecord revoked =
          workspaceService.revokeMember(adminId, workspaceId, editorId);
      assertEquals(WorkspaceRepository.MemberStatus.REVOKED, revoked.status());
      assertTrue(revoked.revokedAt() != null);
      assertEquals(
          7, workspaceService.findWorkspace(adminId, workspaceId).orElseThrow().scopeVersion());
      assertTrue(workspaceService.findWorkspace(editorId, workspaceId).isEmpty());
      assertThrows(
          WorkspaceRepository.WorkspaceAccessDeniedException.class,
          () -> workspaceService.transferOwnership(ownerId, createdWorkspaceId, readerId));
    } finally {
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(adminId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void workspaceRolesScopeLibraryAndContentReadsWritesAndSearch() throws Exception {
    UUID ownerId = createSessionTestUser("Workspace Library Owner");
    UUID adminId = createSessionTestUser("Workspace Library Admin");
    UUID editorId = createSessionTestUser("Workspace Library Editor");
    UUID readerId = createSessionTestUser("Workspace Library Reader");
    UUID outsiderId = createSessionTestUser("Other Workspace Owner");
    UUID workspaceId = null;
    UUID otherWorkspaceId = null;
    try {
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Shared Shelf Team");
      UUID createdWorkspaceId = workspace.id();
      workspaceId = createdWorkspaceId;
      workspaceService.addMember(ownerId, workspaceId, adminId, WorkspaceRepository.Role.ADMIN);
      workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
      workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);

      LibraryRepository.LibraryRecord defaultLibrary =
          libraryService.createLibrary(ownerId, "My Personal Shelf");
      LibraryRepository.LibraryRecord movableLibrary =
          libraryService.createLibrary(ownerId, "Research Shelf");
      LibraryRepository.LibraryRecord workspaceLibrary =
          libraryService.createWorkspaceLibrary(ownerId, createdWorkspaceId, "Collaborative Shelf");
      LibraryRepository.LibraryRecord adminLibrary =
          libraryService.createWorkspaceLibrary(adminId, createdWorkspaceId, "Admin Shelf");
      assertEquals(createdWorkspaceId, workspaceLibrary.workspaceId());
      assertEquals(createdWorkspaceId, adminLibrary.workspaceId());
      assertThrows(
          LibraryRepository.DefaultLibraryCannotMoveException.class,
          () ->
              libraryService.movePersonalLibraryToWorkspace(
                  ownerId, defaultLibrary.id(), createdWorkspaceId));
      LibraryRepository.LibraryRecord movedLibrary =
          libraryService.movePersonalLibraryToWorkspace(
              ownerId, movableLibrary.id(), createdWorkspaceId);
      assertEquals(createdWorkspaceId, movedLibrary.workspaceId());

      LibraryRepository.WorkRecord ownerWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              workspaceLibrary.id(),
              "Shared Story",
              "Author A",
              "vi",
              sha256("shared-owner-work"));
      LibraryRepository.WorkRecord editorWork =
          libraryService.createPrivateImportedWork(
              editorId,
              workspaceLibrary.id(),
              "Editor Story",
              "Author B",
              "vi",
              sha256("shared-editor-work"));
      assertEquals(
          ownerId, editorWork.ownerUserId(), "shared work keeps library creator as data owner");
      libraryService.addTagToWork(editorId, ownerWork.id(), "Shared Tag");

      List<UUID> editorLibraryIds =
          libraryService.listLibraries(editorId).stream()
              .map(LibraryRepository.LibraryRecord::id)
              .toList();
      assertTrue(editorLibraryIds.contains(workspaceLibrary.id()));
      assertTrue(editorLibraryIds.contains(movedLibrary.id()));
      assertFalse(editorLibraryIds.contains(defaultLibrary.id()));
      assertTrue(libraryService.findOwnedWork(readerId, ownerWork.id()).isPresent());
      LibraryService.Page<LibraryRepository.WorkRecord> tagSearch =
          libraryService.listWorks(
              readerId, workspaceLibrary.id(), "Shared", "shared-tag", "active", null, 10);
      assertEquals(
          List.of(ownerWork.id()),
          tagSearch.items().stream().map(LibraryRepository.WorkRecord::id).toList());

      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(editorId, ownerWork.id(), 1, "Shared Volume");
      LibraryRepository.ChapterRecord chapter =
          libraryService.createChapter(
              editorId, ownerWork.id(), volume.id(), 1, "Shared Chapter", "vi");
      LibraryRepository.RevisionRecord revision =
          libraryService.appendChapterRevision(
              editorId,
              chapter.id(),
              "plain_text",
              "Shared chapter content.",
              List.of(
                  new LibraryService.NewSegment(1, "paragraph", "Shared chapter content.", "p1")));
      assertEquals(1, revision.revisionNo());
      assertTrue(libraryService.findOwnedChapter(readerId, chapter.id()).isPresent());
      LibraryRepository.AssetRecord asset =
          libraryService.registerAsset(
              editorId,
              workspaceLibrary.id(),
              "workspace/" + UUID.randomUUID(),
              sha256("workspace-cover"),
              "image/webp",
              4096,
              "shared-cover.webp");
      assertEquals(ownerId, asset.ownerUserId());

      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.createWorkspaceLibrary(
                  editorId, createdWorkspaceId, "Editor Created Shelf"));
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.createPrivateImportedWork(
                  readerId,
                  workspaceLibrary.id(),
                  "Reader Write",
                  "Author",
                  "vi",
                  sha256("reader-write")));
      assertThrows(
          LibraryRepository.WorkNotFoundException.class,
          () -> libraryService.addTagToWork(readerId, ownerWork.id(), "forbidden"));
      assertThrows(
          LibraryRepository.WorkNotFoundException.class,
          () -> libraryService.createVolume(readerId, ownerWork.id(), 2, "Reader Volume"));
      assertThrows(
          LibraryRepository.WorkNotFoundException.class,
          () ->
              libraryService.createChapter(
                  readerId, ownerWork.id(), null, 2, "Reader Chapter", "vi"));
      assertThrows(
          LibraryRepository.ChapterNotFoundException.class,
          () ->
              libraryService.appendChapterRevision(
                  readerId, chapter.id(), "plain_text", "Unauthorized edit", List.of()));
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.registerAsset(
                  readerId,
                  workspaceLibrary.id(),
                  "workspace/reader-forbidden",
                  sha256("reader-asset"),
                  "image/webp",
                  100,
                  "reader.webp"));

      WorkspaceRepository.WorkspaceRecord otherWorkspace =
          workspaceService.createWorkspace(outsiderId, "Separate Team");
      UUID createdOtherWorkspaceId = otherWorkspace.id();
      otherWorkspaceId = createdOtherWorkspaceId;
      LibraryRepository.LibraryRecord otherLibrary =
          libraryService.createWorkspaceLibrary(
              outsiderId, createdOtherWorkspaceId, "Separate Shelf");
      LibraryRepository.WorkRecord otherWork =
          libraryService.createPrivateImportedWork(
              outsiderId,
              otherLibrary.id(),
              "Separate Story",
              "Author",
              "vi",
              sha256("separate-work"));
      assertTrue(libraryService.findOwnedWork(outsiderId, ownerWork.id()).isEmpty());
      assertTrue(libraryService.findOwnedWork(readerId, otherWork.id()).isEmpty());
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.listWorks(
                  outsiderId, workspaceLibrary.id(), "", null, null, null, 10));
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.movePersonalLibraryToWorkspace(
                  outsiderId, defaultLibrary.id(), createdOtherWorkspaceId));

      WorkspaceRepository.MemberRecord revoked =
          workspaceService.revokeMember(ownerId, workspaceId, readerId);
      assertEquals(WorkspaceRepository.MemberStatus.REVOKED, revoked.status());
      assertTrue(libraryService.findOwnedWork(readerId, ownerWork.id()).isEmpty());
      assertFalse(
          libraryService.listLibraries(readerId).stream()
              .anyMatch(library -> library.id().equals(workspaceLibrary.id())));
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryTestData(adminId);
      deleteLibraryTestData(editorId);
      deleteLibraryTestData(readerId);
      deleteLibraryTestData(outsiderId);
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      if (otherWorkspaceId != null) deleteWorkspaceTestData(otherWorkspaceId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(adminId);
      deleteSessionTestUser(editorId);
      deleteSessionTestUser(readerId);
      deleteSessionTestUser(outsiderId);
    }
  }

  @Test
  void workspaceChangesInvalidateLibraryAndWorkCursors() throws Exception {
    UUID ownerId = createSessionTestUser("Cursor Scope Owner");
    UUID memberId = createSessionTestUser("Cursor Scope Member");
    UUID newcomerId = createSessionTestUser("Cursor Scope New Member");
    UUID workspaceId = null;
    try {
      WorkspaceRepository.WorkspaceRecord workspace =
          workspaceService.createWorkspace(ownerId, "Cursor Scope Team");
      workspaceId = workspace.id();
      workspaceService.addMember(ownerId, workspaceId, memberId, WorkspaceRepository.Role.READER);

      LibraryRepository.LibraryRecord sharedLibrary =
          libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Cursor Shared Shelf");
      libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Cursor Second Shelf");
      LibraryRepository.WorkRecord firstWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              sharedLibrary.id(),
              "First Shared Story",
              "Author",
              "vi",
              sha256("scope-first-work"));
      LibraryRepository.WorkRecord secondWork =
          libraryService.createPrivateImportedWork(
              ownerId,
              sharedLibrary.id(),
              "Second Shared Story",
              "Author",
              "vi",
              sha256("scope-second-work"));
      setWorkCreatedAt(firstWork.id(), Instant.parse("2026-02-02T00:00:00Z"));
      setWorkCreatedAt(secondWork.id(), Instant.parse("2026-02-01T00:00:00Z"));

      LibraryService.Page<LibraryRepository.WorkRecord> beforeRoleChange =
          libraryService.listWorks(memberId, sharedLibrary.id(), "", null, null, null, 1);
      assertTrue(beforeRoleChange.nextCursor() != null);
      workspaceService.changeMemberRole(
          ownerId, workspaceId, memberId, WorkspaceRepository.Role.EDITOR);
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  memberId, sharedLibrary.id(), "", null, null, beforeRoleChange.nextCursor(), 1));

      LibraryService.Page<LibraryRepository.WorkRecord> beforeTransfer =
          libraryService.listWorks(memberId, sharedLibrary.id(), "", null, null, null, 1);
      workspaceService.transferOwnership(ownerId, workspaceId, memberId);
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  memberId, sharedLibrary.id(), "", null, null, beforeTransfer.nextCursor(), 1));

      LibraryService.Page<LibraryRepository.WorkRecord> beforeRevocation =
          libraryService.listWorks(ownerId, sharedLibrary.id(), "", null, null, null, 1);
      workspaceService.revokeMember(memberId, workspaceId, ownerId);
      assertThrows(
          LibraryRepository.LibraryNotFoundException.class,
          () ->
              libraryService.listWorks(
                  ownerId, sharedLibrary.id(), "", null, null, beforeRevocation.nextCursor(), 1));
      workspaceService.addMember(memberId, workspaceId, ownerId, WorkspaceRepository.Role.READER);
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  ownerId, sharedLibrary.id(), "", null, null, beforeRevocation.nextCursor(), 1));

      LibraryService.Page<LibraryRepository.LibraryRecord> beforeJoin =
          libraryService.listLibraries(memberId, null, 1);
      assertTrue(beforeJoin.nextCursor() != null);
      workspaceService.addMember(
          memberId, workspaceId, newcomerId, WorkspaceRepository.Role.READER);
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () -> libraryService.listLibraries(memberId, beforeJoin.nextCursor(), 1));

      LibraryRepository.LibraryRecord memberDefault =
          libraryService.createLibrary(memberId, "Cursor Personal");
      LibraryRepository.LibraryRecord movableLibrary =
          libraryService.createLibrary(memberId, "Cursor Movable");
      LibraryRepository.WorkRecord personalFirst =
          libraryService.createPrivateImportedWork(
              memberId,
              movableLibrary.id(),
              "Personal First",
              "Author",
              "vi",
              sha256("scope-personal-first"));
      LibraryRepository.WorkRecord personalSecond =
          libraryService.createPrivateImportedWork(
              memberId,
              movableLibrary.id(),
              "Personal Second",
              "Author",
              "vi",
              sha256("scope-personal-second"));
      setWorkCreatedAt(personalFirst.id(), Instant.parse("2026-03-02T00:00:00Z"));
      setWorkCreatedAt(personalSecond.id(), Instant.parse("2026-03-01T00:00:00Z"));
      LibraryService.Page<LibraryRepository.WorkRecord> beforeLibraryMove =
          libraryService.listWorks(memberId, movableLibrary.id(), "", null, null, null, 1);
      assertTrue(beforeLibraryMove.nextCursor() != null);
      assertTrue(memberDefault.isDefault());
      libraryService.movePersonalLibraryToWorkspace(memberId, movableLibrary.id(), workspaceId);
      assertThrows(
          LibraryCursorCodec.InvalidCursorException.class,
          () ->
              libraryService.listWorks(
                  memberId,
                  movableLibrary.id(),
                  "",
                  null,
                  null,
                  beforeLibraryMove.nextCursor(),
                  1));
    } finally {
      deleteLibraryTestData(ownerId);
      deleteLibraryTestData(memberId);
      deleteLibraryTestData(newcomerId);
      if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(memberId);
      deleteSessionTestUser(newcomerId);
    }
  }

  @Test
  void currentPolicyRouteReturnsTheLatestEffectiveVersionAndSameOriginDocuments() throws Exception {
    MvcResult current = mockMvc.perform(get("/api/v1/auth/policies/current")).andReturn();
    assertEquals(200, current.getResponse().getStatus());
    assertTrue(current.getResponse().getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", current.getResponse().getHeader("Pragma"));
    JsonNode policies = objectMapper.readTree(current.getResponse().getContentAsString());
    assertEquals("terms-current-1", policies.path("terms").path("version").asText());
    assertEquals("privacy-current-1", policies.path("privacyNotice").path("version").asText());
    assertTrue(
        Instant.parse(policies.path("terms").path("effectiveAt").asText()).isBefore(Instant.now()));
    assertEquals(
        "/api/v1/auth/policies/terms/terms-current-1",
        policies.path("terms").path("documentUrl").asText());
    assertEquals(
        "/api/v1/auth/policies/privacy-notice/privacy-current-1",
        policies.path("privacyNotice").path("documentUrl").asText());

    MvcResult document =
        mockMvc.perform(get("/api/v1/auth/policies/terms/terms-current-1")).andReturn();
    assertEquals(200, document.getResponse().getStatus());
    assertTrue(document.getResponse().getContentType().startsWith("text/markdown"));
    assertTrue(document.getResponse().getHeader("Cache-Control").contains("max-age=3600"));
    assertEquals("nosniff", document.getResponse().getHeader("X-Content-Type-Options"));
    assertTrue(document.getResponse().getContentAsString().contains("Synthetic test document"));

    MvcResult unknownVersion =
        mockMvc.perform(get("/api/v1/auth/policies/terms/missing-version")).andReturn();
    assertEquals(404, unknownVersion.getResponse().getStatus());
    assertTrue(
        unknownVersion.getResponse().getContentAsString().contains("POLICY_DOCUMENT_NOT_FOUND"));

    MvcResult futureVersion =
        mockMvc.perform(get("/api/v1/auth/policies/terms/terms-future-2")).andReturn();
    assertEquals(404, futureVersion.getResponse().getStatus());
    assertTrue(
        futureVersion.getResponse().getContentAsString().contains("POLICY_DOCUMENT_NOT_FOUND"));
  }

  @Test
  void policyAcceptanceRequiresBothCurrentExplicitChoicesIsAtomicAndIdempotent() throws Exception {
    UUID userId = createSessionTestUser("Policy Acceptance Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
      jakarta.servlet.http.Cookie cookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue());

      MvcResult missingChoice =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "termsVersion", "terms-current-1",
                                  "privacyNoticeVersion", "privacy-current-1",
                                  "termsAccepted", true)))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(cookie))
              .andReturn();
      assertEquals(422, missingChoice.getResponse().getStatus());
      assertTrue(
          missingChoice.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      assertEquals(0, policyAcceptanceCount(userId));

      MvcResult stale =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "termsVersion",
                                  "terms-old",
                                  "privacyNoticeVersion",
                                  "privacy-current-1",
                                  "termsAccepted",
                                  true,
                                  "privacyNoticeAcknowledged",
                                  true)))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(cookie))
              .andReturn();
      assertEquals(409, stale.getResponse().getStatus());
      JsonNode staleProblem = objectMapper.readTree(stale.getResponse().getContentAsString());
      assertEquals("POLICY_VERSION_STALE", staleProblem.path("code").asText());
      assertEquals("terms-current-1", staleProblem.path("currentTermsVersion").asText());
      assertEquals(0, policyAcceptanceCount(userId));

      Map<String, Object> acceptedBody =
          Map.of(
              "termsVersion",
              "terms-current-1",
              "privacyNoticeVersion",
              "privacy-current-1",
              "termsAccepted",
              true,
              "privacyNoticeAcknowledged",
              true);
      MvcResult accepted =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(acceptedBody))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(cookie))
              .andReturn();
      assertEquals(200, accepted.getResponse().getStatus());
      assertTrue(accepted.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode acceptance = objectMapper.readTree(accepted.getResponse().getContentAsString());
      Instant termsAcceptedAt = Instant.parse(acceptance.path("termsAcceptedAt").asText());
      Instant privacyAcknowledgedAt =
          Instant.parse(acceptance.path("privacyNoticeAcknowledgedAt").asText());
      assertTrue(termsAcceptedAt.isBefore(Instant.now().plusSeconds(1)));
      assertTrue(privacyAcknowledgedAt.isBefore(Instant.now().plusSeconds(1)));
      assertEquals(2, policyAcceptanceCount(userId));
      assertEquals(2, policyAcceptanceCountBySource(userId, "web-onboarding"));

      MvcResult retry =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(acceptedBody))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(cookie))
              .andReturn();
      assertEquals(200, retry.getResponse().getStatus());
      JsonNode retriedAcceptance = objectMapper.readTree(retry.getResponse().getContentAsString());
      assertEquals(
          acceptance.path("termsAcceptedAt").asText(),
          retriedAcceptance.path("termsAcceptedAt").asText());
      assertEquals(
          acceptance.path("privacyNoticeAcknowledgedAt").asText(),
          retriedAcceptance.path("privacyNoticeAcknowledgedAt").asText());
      assertEquals(2, policyAcceptanceCount(userId));

      MvcResult currentUser = mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn();
      assertTrue(
          objectMapper
              .readTree(currentUser.getResponse().getContentAsString())
              .path("policy")
              .path("requiredAcceptanceComplete")
              .asBoolean());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void policyRoutesFailClosedUntilBothApprovedDocumentsAreProvisioned() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement deleteDocuments =
            connection.prepareStatement("DELETE FROM policy_documents")) {
      assertEquals(5, deleteDocuments.executeUpdate());
    }

    UUID userId = createSessionTestUser("Unconfigured Policy Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
      MvcResult current = mockMvc.perform(get("/api/v1/auth/policies/current")).andReturn();
      assertEquals(503, current.getResponse().getStatus());
      assertTrue(current.getResponse().getContentAsString().contains("POLICY_NOT_CONFIGURED"));

      MvcResult acceptance =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "termsVersion",
                                  "terms-current-1",
                                  "privacyNoticeVersion",
                                  "privacy-current-1",
                                  "termsAccepted",
                                  true,
                                  "privacyNoticeAcknowledged",
                                  true)))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(503, acceptance.getResponse().getStatus());
      assertTrue(acceptance.getResponse().getContentAsString().contains("POLICY_NOT_CONFIGURED"));
      assertEquals(0, policyAcceptanceCount(userId));
    } finally {
      deleteSessionTestUser(userId);
      insertPolicyTestDocuments();
    }
  }

  @AfterAll
  void integrationFixturesAreRemoved() throws SQLException, java.io.IOException {
    for (String table :
        List.of(
            "users",
            "external_identities",
            "sessions",
            "google_auth_attempts",
            "google_auth_bootstrap_rate_limits",
            "import_actor_rate_limits",
            "policy_acceptances",
            "import_jobs",
            "import_parse_jobs",
            "import_commits",
            "import_rights_acceptances",
            "import_rights_statements",
            "publication_rights_policy_acceptances",
            "publication_rights_policy_acceptance_idempotency",
            "publication_rights_policy_documents",
            "auth_events",
            "session_revoke_confirmations",
            "workspace_members",
            "workspaces",
            "library_collection_works",
            "reader_bookmark_idempotency",
            "reader_bookmarks",
            "reader_preferences",
            "reader_progress",
            "library_reading_states",
            "library_collections",
            "libraries",
            "works",
            "volumes",
            "chapters",
            "chapter_revisions",
            "segments",
            "assets",
            "tags",
            "work_tags")) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
          ResultSet rows = count.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "integration fixtures remain in " + table);
      }
    }
    assertEquals(
        0, stagedUploadCount(), "integration upload objects remain in local staging storage");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement deleteDocuments =
            connection.prepareStatement("DELETE FROM policy_documents")) {
      deleteDocuments.executeUpdate();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement count =
            connection.prepareStatement("SELECT COUNT(*) FROM policy_documents");
        ResultSet rows = count.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1), "policy document test fixtures remain");
    }
  }

  @Test
  void verifiedEmailIsCaseInsensitiveUniqueButCanBeReusedAfterDeletion() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertUser(connection, "First Reader", "Reader@example.test", "active", false);

        Savepoint beforeDuplicate = connection.setSavepoint();
        SQLException duplicateEmail =
            assertThrows(
                SQLException.class,
                () ->
                    insertUser(
                        connection, "Second Reader", "reader@example.test", "active", false));
        assertEquals("23505", duplicateEmail.getSQLState(), "expected unique-index violation");
        connection.rollback(beforeDuplicate);

        insertUser(connection, "Deleted Reader", "reader@example.test", "deleted", true);
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void authAttemptRejectsProtocolRelativeReturnPath() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement insert =
          connection.prepareStatement(
              """
                    INSERT INTO google_auth_attempts (id, nonce_hash, safe_return_path, expires_at, correlation_id)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
        insert.setObject(1, UUID.randomUUID());
        insert.setBytes(2, new byte[32]);
        insert.setString(3, "//attacker.example/collect");
        insert.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(300)));
        insert.setObject(5, UUID.randomUUID());

        SQLException invalidPath = assertThrows(SQLException.class, insert::executeUpdate);
        assertEquals("23514", invalidPath.getSQLState(), "expected CHECK constraint violation");
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void googleAuthAttemptStoresOnlyHashedNonceAndCorrelatesTheSafePath() throws Exception {
    Instant beforeCreate = Instant.now();
    UUID correlationId = UUID.randomUUID();

    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/reader?work=private-123", correlationId);
    byte[] nonceHash = sha256(attempt.nonce());
    try {
      assertEquals(
          43, attempt.nonce().length(), "32 random bytes should use unpadded base64url encoding");
      assertEquals("/app/reader?work=private-123", attempt.returnTo());
      assertEquals(correlationId, attempt.correlationId());
      long secondsUntilExpiry =
          java.time.Duration.between(beforeCreate, attempt.expiresAt()).toSeconds();
      assertTrue(
          secondsUntilExpiry >= 290 && secondsUntilExpiry <= 300,
          "new attempts should expire in approximately five minutes");

      try (Connection connection = dataSource.getConnection();
          PreparedStatement query =
              connection.prepareStatement(
                  """
                         SELECT nonce_hash, safe_return_path, correlation_id,
                                expires_at > created_at AS expires_after_creation
                         FROM google_auth_attempts
                         WHERE nonce_hash = ?
                         """)) {
        query.setBytes(1, nonceHash);
        try (ResultSet storedAttempt = query.executeQuery()) {
          assertTrue(storedAttempt.next(), "the auth attempt must be persisted");
          byte[] storedHash = storedAttempt.getBytes("nonce_hash");
          assertEquals(32, storedHash.length);
          assertEquals("/app/reader?work=private-123", storedAttempt.getString("safe_return_path"));
          assertEquals(correlationId, storedAttempt.getObject("correlation_id", UUID.class));
          assertTrue(storedAttempt.getBoolean("expires_after_creation"));
          assertFalse(
              java.util.Arrays.equals(
                  attempt.nonce().getBytes(StandardCharsets.UTF_8), storedHash));
        }
      }
    } finally {
      deleteAuthAttempt(nonceHash);
    }
  }

  @Test
  void googleAuthAttemptNoncesAreUniqueAndUnsafeReturnPathsFallBackToHome() throws Exception {
    Set<String> nonces = new HashSet<>();
    try {
      for (int index = 0; index < 16; index++) {
        GoogleAuthAttemptService.CreatedAttempt attempt =
            googleAuthAttemptService.create("//attacker.example/collect", UUID.randomUUID());
        assertTrue(nonces.add(attempt.nonce()), "generated auth attempt nonces must be unique");
        assertEquals("/app/home", attempt.returnTo());
      }
    } finally {
      for (String nonce : nonces) {
        deleteAuthAttempt(sha256(nonce));
      }
    }
  }

  @Test
  void googleAuthAttemptIsConsumedAtMostOnce() throws Exception {
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/library", UUID.randomUUID());
    byte[] nonceHash = sha256(attempt.nonce());
    try {
      Optional<GoogleAuthAttemptService.ConsumedAttempt> consumed =
          googleAuthAttemptService.consumeOnce(attempt.nonce());

      assertTrue(consumed.isPresent());
      assertEquals("/app/library", consumed.orElseThrow().returnTo());
      assertEquals(attempt.correlationId(), consumed.orElseThrow().correlationId());
      assertTrue(
          googleAuthAttemptService.consumeOnce(attempt.nonce()).isEmpty(),
          "a replay must not consume the same attempt twice");
      assertTrue(googleAuthAttemptService.consumeOnce("not-a-valid-nonce").isEmpty());
    } finally {
      deleteAuthAttempt(nonceHash);
    }
  }

  @Test
  void expiredGoogleAuthAttemptCannotBeConsumed() throws Exception {
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/home", UUID.randomUUID());
    byte[] nonceHash = sha256(attempt.nonce());
    try {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement expire =
              connection.prepareStatement(
                  """
                         UPDATE google_auth_attempts
                         SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 millisecond'
                         WHERE nonce_hash = ?
                         """)) {
        expire.setBytes(1, nonceHash);
        assertEquals(1, expire.executeUpdate());
      }

      assertTrue(
          googleAuthAttemptService.consumeOnce(attempt.nonce()).isEmpty(),
          "an expired attempt must not return its stored path");
    } finally {
      deleteAuthAttempt(nonceHash);
    }
  }

  @Test
  void concurrentGoogleAuthAttemptConsumersHaveOnlyOneWinner() throws Exception {
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/reader", UUID.randomUUID());
    byte[] nonceHash = sha256(attempt.nonce());
    int consumers = 5;
    ExecutorService executor = Executors.newFixedThreadPool(consumers);
    CountDownLatch ready = new CountDownLatch(consumers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Optional<GoogleAuthAttemptService.ConsumedAttempt>>> results = new ArrayList<>();

    try {
      for (int index = 0; index < consumers; index++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("consumer start signal timed out");
                  }
                  return googleAuthAttemptService.consumeOnce(attempt.nonce());
                }));
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS), "all consumers should reach the start barrier");
      start.countDown();
      long winners = 0;
      for (Future<Optional<GoogleAuthAttemptService.ConsumedAttempt>> result : results) {
        if (result.get(10, TimeUnit.SECONDS).isPresent()) {
          winners++;
        }
      }
      assertEquals(1, winners, "the database compare-and-set should admit one concurrent consumer");
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      deleteAuthAttempt(nonceHash);
    }
  }

  @Test
  void googleAuthBootstrapRateLimiterAllowsTenRequestsThenRefillsOneToken() throws Exception {
    byte[] rateLimitKeyHash = sha256(UUID.randomUUID().toString());
    try {
      for (int request = 0; request < 10; request++) {
        assertTrue(
            googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
            "the initial bucket should allow ten bootstrap requests");
      }
      assertFalse(
          googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
          "the eleventh request should be throttled");

      try (Connection connection = dataSource.getConnection();
          PreparedStatement refill =
              connection.prepareStatement(
                  """
                         UPDATE google_auth_bootstrap_rate_limits
                         SET available_tokens = 0,
                             refilled_at = clock_timestamp() - INTERVAL '24 hours'
                         WHERE rate_limit_key_hash = ?
                         """)) {
        refill.setBytes(1, rateLimitKeyHash);
        assertEquals(1, refill.executeUpdate());
      }

      assertTrue(
          googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
          "a full configured refill interval should restore one request");
      assertFalse(
          googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
          "the refilled token should be consumed once");
    } finally {
      deleteRateLimitBucket(rateLimitKeyHash);
    }
  }

  @Test
  void googleBootstrapHttpEndpointReturnsFreshSafeAttempt() throws Exception {
    String clientAddress = bootstrapTestClientAddress();
    List<UUID> correlationIds = new ArrayList<>();
    try {
      MvcResult result =
          mockMvc
              .perform(
                  get("/api/v1/auth/google/bootstrap")
                      .param("returnTo", "/library/recent?sort=updated")
                      .param("locale", "en")
                      .with(
                          request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                          }))
              .andReturn();
      UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
      correlationIds.add(correlationId);

      assertEquals(200, result.getResponse().getStatus());
      assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", result.getResponse().getHeader("Pragma"));
      assertNull(result.getResponse().getHeader("Set-Cookie"));

      JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
      assertEquals(
          "novel-web-integration-test.apps.googleusercontent.com", body.path("clientId").asText());
      assertEquals(
          "https://api.example.test/api/v1/auth/google/credential", body.path("loginUri").asText());
      assertTrue(body.path("nonce").asText().matches("[A-Za-z0-9_-]{43}"));
      assertEquals("en", body.path("locale").asText());
      assertTrue(Instant.parse(body.path("attemptExpiresAt").asText()).isAfter(Instant.now()));
      assertEquals(
          "/library/recent?sort=updated", storedBootstrapReturnPath(body.path("nonce").asText()));
      assertEquals(1, googleAuthAttemptCount(correlationId));
    } finally {
      deleteBootstrapFixtures(clientAddress, correlationIds);
    }
  }

  @Test
  void googleCredentialCallbackRejectsMissingCsrfCookieBeforeDecodingCredential() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/google/credential")
                    .header("Origin", "https://app.example.test")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(
                        "credential=synthetic-invalid-token&g_csrf_token=synthetic-csrf-token"))
            .andReturn();

    assertEquals(401, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"));
    assertTrue(result.getResponse().getContentAsString().contains("SIGN_IN_FAILED"));
    assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void googleCredentialCallbackRejectsMissingOriginAndUnsupportedMediaType() throws Exception {
    MvcResult missingOrigin =
        mockMvc
            .perform(
                post("/api/v1/auth/google/credential")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(
                        "credential=synthetic-invalid-token&g_csrf_token=synthetic-csrf-token"))
            .andReturn();
    assertEquals(403, missingOrigin.getResponse().getStatus());
    assertTrue(missingOrigin.getResponse().getContentAsString().contains("INVALID_ORIGIN"));

    MvcResult wrongContentType =
        mockMvc
            .perform(
                post("/api/v1/auth/google/credential")
                    .header("Origin", "https://app.example.test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();
    assertEquals(415, wrongContentType.getResponse().getStatus());
    assertTrue(
        wrongContentType.getResponse().getContentAsString().contains("UNSUPPORTED_MEDIA_TYPE"));
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void googleCredentialCallbackRejectsOversizedAndDuplicateCredentialForms() throws Exception {
    String oversized = "x".repeat(24 * 1024 + 1);
    MvcResult tooLarge =
        mockMvc
            .perform(
                post("/api/v1/auth/google/credential")
                    .header("Origin", "https://app.example.test")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(oversized))
            .andReturn();
    assertEquals(413, tooLarge.getResponse().getStatus());

    MvcResult duplicate =
        mockMvc
            .perform(
                post("/api/v1/auth/google/credential")
                    .header("Origin", "https://app.example.test")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("credential=first&credential=second&g_csrf_token=csrf-token")
                    .cookie(new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token")))
            .andReturn();
    assertEquals(400, duplicate.getResponse().getStatus());
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void googleCredentialCallbackCreatesIdentitySessionAndRedirectsToStoredPath() throws Exception {
    String subject = "callback-" + UUID.randomUUID();
    String email = subject + "@example.test";
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/library/recent?sort=updated", UUID.randomUUID());
    when(jwtDecoder.decode("synthetic-google-id-token"))
        .thenReturn(googleJwt(attempt.nonce(), subject, email));

    try {
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/auth/google/credential")
                      .header("Origin", "https://app.example.test")
                      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                      .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                      .cookie(new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token")))
              .andReturn();

      assertEquals(303, result.getResponse().getStatus());
      assertEquals("/library/recent?sort=updated", result.getResponse().getHeader("Location"));
      assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", result.getResponse().getHeader("Pragma"));
      assertEquals("no-referrer", result.getResponse().getHeader("Referrer-Policy"));
      assertEquals("", result.getResponse().getContentAsString());

      String setCookie = result.getResponse().getHeader("Set-Cookie");
      assertTrue(setCookie.startsWith("__Host-novel_session="));
      assertTrue(setCookie.contains("Path=/"));
      assertTrue(setCookie.contains("Secure"));
      assertTrue(setCookie.contains("HttpOnly"));
      assertTrue(setCookie.contains("SameSite=Lax"));
      String sessionToken =
          setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
      assertTrue(sessionToken.matches("[A-Za-z0-9_-]{43}"));

      UUID userId = userIdForGoogleSubject(subject);
      assertEquals(1, identityCount(subject));
      assertTrue(sessionHashMatches(sessionToken, userId), "only the cookie-token hash is stored");
      assertEquals(userId, sessionService.validateAndTouch(sessionToken).orElseThrow().userId());
    } finally {
      deleteAuthAttempt(sha256(attempt.nonce()));
      deleteGoogleSignInFixtures(subject);
    }
  }

  @Test
  void googleCredentialCallbackRotatesAnExistingSessionForTheSameAccount() throws Exception {
    String subject = "rotate-callback-" + UUID.randomUUID();
    String email = subject + "@example.test";
    GoogleIdentityService.IdentityAccount account =
        googleIdentityService.findOrCreate(verifiedIdentity(subject, email, "Callback Reader"));
    SessionService.IssuedSession previous = sessionService.create(account.userId()).orElseThrow();
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/home", UUID.randomUUID());
    when(jwtDecoder.decode("synthetic-google-id-token"))
        .thenReturn(googleJwt(attempt.nonce(), subject, email));

    try {
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/auth/google/credential")
                      .header("Origin", "https://app.example.test")
                      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                      .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                      .cookie(
                          new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token"),
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", previous.cookieValue())))
              .andReturn();

      assertEquals(303, result.getResponse().getStatus());
      String setCookie = result.getResponse().getHeader("Set-Cookie");
      String rotatedToken =
          setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
      assertNotEquals(previous.cookieValue(), rotatedToken);
      assertTrue(sessionService.validateAndTouch(previous.cookieValue()).isEmpty());
      assertEquals(
          account.userId(), sessionService.validateAndTouch(rotatedToken).orElseThrow().userId());
      assertTrue(sessionHashMatches(rotatedToken, account.userId()));
      assertEquals(1, activeSessionCount(account.userId()));
    } finally {
      deleteAuthAttempt(sha256(attempt.nonce()));
      deleteGoogleSignInFixtures(subject);
    }
  }

  @Test
  void googleCredentialCallbackRevokesThePreviousAccountSessionOnAccountSwitch() throws Exception {
    String previousSubject = "previous-callback-" + UUID.randomUUID();
    String previousEmail = previousSubject + "@example.test";
    GoogleIdentityService.IdentityAccount previousAccount =
        googleIdentityService.findOrCreate(
            verifiedIdentity(previousSubject, previousEmail, "Previous Reader"));
    SessionService.IssuedSession previous =
        sessionService.create(previousAccount.userId()).orElseThrow();

    String nextSubject = "next-callback-" + UUID.randomUUID();
    String nextEmail = nextSubject + "@example.test";
    GoogleAuthAttemptService.CreatedAttempt attempt =
        googleAuthAttemptService.create("/app/home", UUID.randomUUID());
    when(jwtDecoder.decode("synthetic-google-id-token"))
        .thenReturn(googleJwt(attempt.nonce(), nextSubject, nextEmail));

    try {
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/auth/google/credential")
                      .header("Origin", "https://app.example.test")
                      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                      .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                      .cookie(
                          new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token"),
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", previous.cookieValue())))
              .andReturn();

      assertEquals(303, result.getResponse().getStatus());
      String setCookie = result.getResponse().getHeader("Set-Cookie");
      String nextToken =
          setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
      UUID nextUserId = userIdForGoogleSubject(nextSubject);
      assertTrue(sessionService.validateAndTouch(previous.cookieValue()).isEmpty());
      assertEquals(nextUserId, sessionService.validateAndTouch(nextToken).orElseThrow().userId());
      assertEquals(0, activeSessionCount(previousAccount.userId()));
      assertEquals(1, activeSessionCount(nextUserId));
    } finally {
      deleteAuthAttempt(sha256(attempt.nonce()));
      deleteGoogleSignInFixtures(previousSubject);
      deleteGoogleSignInFixtures(nextSubject);
    }
  }

  @Test
  void csrfTokenRouteRequiresSessionAndChangesWhenSessionRotates() throws Exception {
    MvcResult unauthenticated = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
    assertEquals(401, unauthenticated.getResponse().getStatus());
    assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

    UUID userId = createSessionTestUser("CSRF Route Reader");
    try {
      SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
      MvcResult first =
          mockMvc
              .perform(
                  get("/api/v1/auth/csrf")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", original.cookieValue())))
              .andReturn();

      assertEquals(200, first.getResponse().getStatus());
      assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", first.getResponse().getHeader("Pragma"));
      assertNull(first.getResponse().getHeader("Set-Cookie"));
      JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
      String firstToken = firstBody.path("token").asText();
      assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
      assertTrue(csrfTokenService.matches(original.cookieValue(), firstToken));
      assertEquals(original.expiresAt(), Instant.parse(firstBody.path("expiresAt").asText()));

      SessionService.IssuedSession rotated =
          sessionService.rotate(original.cookieValue()).orElseThrow();
      MvcResult second =
          mockMvc
              .perform(
                  get("/api/v1/auth/csrf")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", rotated.cookieValue())))
              .andReturn();
      JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
      String secondToken = secondBody.path("token").asText();
      assertEquals(200, second.getResponse().getStatus());
      assertNotEquals(firstToken, secondToken);
      assertTrue(csrfTokenService.matches(rotated.cookieValue(), secondToken));

      MvcResult revokedMutation =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}")
                      .header("X-CSRF-Token", firstToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", original.cookieValue())))
              .andReturn();
      assertEquals(401, revokedMutation.getResponse().getStatus());
      assertTrue(revokedMutation.getResponse().getContentAsString().contains("UNAUTHENTICATED"));
      assertFalse(revokedMutation.getResponse().getContentAsString().contains("CSRF_FAILED"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void cookieAuthenticatedMutationRequiresTheSessionBoundCsrfHeader() throws Exception {
    UUID userId = createSessionTestUser("CSRF Mutation Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());

      MvcResult invalid =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}")
                      .header("X-CSRF-Token", "invalid-token")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(403, invalid.getResponse().getStatus());
      assertTrue(invalid.getResponse().getContentAsString().contains("CSRF_FAILED"));

      MvcResult validCsrf =
          mockMvc
              .perform(
                  post("/api/v1/auth/policies/accept")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(422, validCsrf.getResponse().getStatus());
      assertTrue(
          validCsrf.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
      assertFalse(validCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void currentUserRequiresSessionAndReturnsOnlyTheSessionOwnersCurrentProfile() throws Exception {
    MvcResult unauthenticated = mockMvc.perform(get("/api/v1/auth/me")).andReturn();
    assertEquals(401, unauthenticated.getResponse().getStatus());
    assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

    UUID userId = createSessionTestUser("Current User Reader");
    try {
      addSessionTestGoogleIdentity(userId);
      addPolicyAcceptance(userId, "TERMS", "terms-current-1", "TERMS_ACCEPTED");
      addPolicyAcceptance(
          userId, "PRIVACY_NOTICE", "privacy-current-1", "PRIVACY_NOTICE_ACKNOWLEDGED");
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();

      MvcResult authenticated =
          mockMvc
              .perform(
                  get("/api/v1/auth/me")
                      .queryParam("userId", UUID.randomUUID().toString())
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();

      assertEquals(200, authenticated.getResponse().getStatus());
      assertTrue(authenticated.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", authenticated.getResponse().getHeader("Pragma"));
      assertNull(authenticated.getResponse().getHeader("Set-Cookie"));
      JsonNode body = objectMapper.readTree(authenticated.getResponse().getContentAsString());
      assertEquals(userId.toString(), body.path("user").path("id").asText());
      assertEquals("Current User Reader", body.path("user").path("displayName").asText());
      assertEquals("active", body.path("user").path("status").asText());
      assertEquals(
          "session-" + userId + "@example.test", body.path("user").path("contactEmail").asText());
      assertTrue(body.path("user").path("avatarUrl").isNull());
      assertEquals(
          List.of("google"),
          objectMapper.convertValue(
              body.path("identityProviders"),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
      assertEquals("terms-current-1", body.path("policy").path("termsAcceptedVersion").asText());
      assertEquals(
          "privacy-current-1",
          body.path("policy").path("privacyNoticeAcknowledgedVersion").asText());
      assertTrue(body.path("policy").path("requiredAcceptanceComplete").asBoolean());
      assertEquals("personal", body.path("roles").get(0).path("scope").asText());
      assertEquals(0, body.path("roles").get(0).path("permissions").size());
      assertFalse(body.path("features").path("privateLibraryEnabled").asBoolean());
      assertFalse(body.path("features").path("importEnabled").asBoolean());
      assertFalse(body.path("features").path("readerEnabled").asBoolean());
      assertTrue(body.path("features").path("cultivationEnabled").asBoolean());
      assertTrue(body.path("features").path("commerceSandboxEnabled").asBoolean());
      assertFalse(authenticated.getResponse().getContentAsString().contains(session.cookieValue()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void currentUserMarksOldPolicyVersionsIncomplete() throws Exception {
    UUID userId = createSessionTestUser("Old Policy Reader");
    try {
      addPolicyAcceptance(userId, "TERMS", "terms-old", "TERMS_ACCEPTED");
      addPolicyAcceptance(userId, "PRIVACY_NOTICE", "privacy-old", "PRIVACY_NOTICE_ACKNOWLEDGED");
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();

      MvcResult response =
          mockMvc
              .perform(
                  get("/api/v1/auth/me")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();

      assertEquals(200, response.getResponse().getStatus());
      JsonNode policy =
          objectMapper.readTree(response.getResponse().getContentAsString()).path("policy");
      assertEquals("terms-old", policy.path("termsAcceptedVersion").asText());
      assertEquals("privacy-old", policy.path("privacyNoticeAcknowledgedVersion").asText());
      assertFalse(policy.path("requiredAcceptanceComplete").asBoolean());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void currentUserRejectsAUserWhoseAccountWasSuspendedAfterSessionIssue() throws Exception {
    UUID userId = createSessionTestUser("Suspended Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      try (Connection connection = dataSource.getConnection();
          PreparedStatement suspend =
              connection.prepareStatement("UPDATE users SET status = 'suspended' WHERE id = ?")) {
        suspend.setObject(1, userId);
        assertEquals(1, suspend.executeUpdate());
      }

      MvcResult response =
          mockMvc
              .perform(
                  get("/api/v1/auth/me")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(401, response.getResponse().getStatus());
      assertTrue(response.getResponse().getContentAsString().contains("UNAUTHENTICATED"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void logoutRevokesTheSessionClearsTheCookieAndCanBeRepeated() throws Exception {
    UUID userId = createSessionTestUser("Logout Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());

      MvcResult first =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(204, first.getResponse().getStatus());
      assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(first.getResponse().getHeader("Set-Cookie").contains("__Host-novel_session="));
      assertTrue(first.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
      assertTrue(first.getResponse().getHeader("Set-Cookie").contains("Secure"));
      assertTrue(first.getResponse().getHeader("Set-Cookie").contains("HttpOnly"));
      assertTrue(first.getResponse().getHeader("Set-Cookie").contains("SameSite=Lax"));
      assertFalse(first.getResponse().getHeader("Set-Cookie").contains("Domain="));
      assertTrue(sessionService.validateAndTouch(session.cookieValue()).isEmpty());

      MvcResult repeated =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(204, repeated.getResponse().getStatus());
      assertTrue(repeated.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void logoutWithoutASessionIsIdempotentAndStillClearsCookie() throws Exception {
    MvcResult response = mockMvc.perform(post("/api/v1/auth/logout")).andReturn();
    assertEquals(204, response.getResponse().getStatus());
    assertTrue(response.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
  }

  @Test
  void logoutRejectsInvalidCsrfWithoutRevokingTheCurrentSession() throws Exception {
    UUID userId = createSessionTestUser("Logout CSRF Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      MvcResult response =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout")
                      .header("X-CSRF-Token", "invalid-token")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();

      assertEquals(403, response.getResponse().getStatus());
      assertTrue(response.getResponse().getContentAsString().contains("CSRF_FAILED"));
      assertNull(response.getResponse().getHeader("Set-Cookie"));
      assertTrue(sessionService.validateAndTouch(session.cookieValue()).isPresent());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void logoutAllConfirmationIsNoStoreHashedBoundAndReplacedPerSession() throws Exception {
    MvcResult unauthenticated =
        mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")).andReturn();
    assertEquals(403, unauthenticated.getResponse().getStatus());
    assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));

    UUID userId = createSessionTestUser("Logout All Confirmation Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
      MvcResult first =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all/confirmation")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();

      assertEquals(200, first.getResponse().getStatus());
      assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertEquals("no-cache", first.getResponse().getHeader("Pragma"));
      JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
      String firstToken = firstBody.path("confirmationToken").asText();
      assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
      Instant firstExpiry = Instant.parse(firstBody.path("expiresAt").asText());
      assertTrue(firstExpiry.isAfter(Instant.now()));
      assertTrue(firstExpiry.isBefore(Instant.now().plusSeconds(6 * 60)));
      assertTrue(confirmationHashStoredForUser(firstToken, userId, session.cookieValue()));

      MvcResult replacement =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all/confirmation")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(200, replacement.getResponse().getStatus());
      String replacementToken =
          objectMapper
              .readTree(replacement.getResponse().getContentAsString())
              .path("confirmationToken")
              .asText();
      assertFalse(confirmationHashExists(firstToken));
      assertTrue(confirmationHashExists(replacementToken));
      assertEquals(1, activeSessionCount(userId));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void logoutAllRejectsWrongUserOrSessionThenRevokesOnlyTheBoundUsersSessions() throws Exception {
    UUID userId = createSessionTestUser("Logout All Owner");
    UUID otherUserId = createSessionTestUser("Other Logout All Owner");
    try {
      SessionService.IssuedSession current = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession otherDevice = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession otherUserSession =
          sessionService.create(otherUserId).orElseThrow();
      String currentCsrf = csrfTokenService.tokenFor(current.cookieValue());
      MvcResult confirmationResponse =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all/confirmation")
                      .header("X-CSRF-Token", currentCsrf)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", current.cookieValue())))
              .andReturn();
      String confirmationToken =
          objectMapper
              .readTree(confirmationResponse.getResponse().getContentAsString())
              .path("confirmationToken")
              .asText();

      MvcResult wrongSession =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("confirmationToken", confirmationToken)))
                      .header("X-CSRF-Token", csrfTokenService.tokenFor(otherDevice.cookieValue()))
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", otherDevice.cookieValue())))
              .andReturn();
      assertEquals(403, wrongSession.getResponse().getStatus());
      assertTrue(
          wrongSession
              .getResponse()
              .getContentAsString()
              .contains("LOGOUT_ALL_CONFIRMATION_INVALID"));

      MvcResult wrongUser =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("confirmationToken", confirmationToken)))
                      .header(
                          "X-CSRF-Token", csrfTokenService.tokenFor(otherUserSession.cookieValue()))
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", otherUserSession.cookieValue())))
              .andReturn();
      assertEquals(403, wrongUser.getResponse().getStatus());
      assertTrue(
          wrongUser.getResponse().getContentAsString().contains("LOGOUT_ALL_CONFIRMATION_INVALID"));
      assertEquals(2, activeSessionCount(userId));
      assertEquals(1, activeSessionCount(otherUserId));

      MvcResult success =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("confirmationToken", confirmationToken)))
                      .header("X-CSRF-Token", currentCsrf)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", current.cookieValue())))
              .andReturn();
      assertEquals(204, success.getResponse().getStatus());
      assertTrue(success.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(success.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
      assertEquals(0, activeSessionCount(userId));
      assertEquals(1, activeSessionCount(otherUserId));

      MvcResult replay =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("confirmationToken", confirmationToken)))
                      .header("X-CSRF-Token", currentCsrf)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", current.cookieValue())))
              .andReturn();
      assertEquals(401, replay.getResponse().getStatus());
      assertEquals(1, activeSessionCount(otherUserId));
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void expiredLogoutAllConfirmationDoesNotRevokeSessions() throws Exception {
    UUID userId = createSessionTestUser("Expired Logout All Reader");
    try {
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
      MvcResult confirmationResponse =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all/confirmation")
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      String confirmationToken =
          objectMapper
              .readTree(confirmationResponse.getResponse().getContentAsString())
              .path("confirmationToken")
              .asText();
      try (Connection connection = dataSource.getConnection();
          PreparedStatement expire =
              connection.prepareStatement(
                  """
                         UPDATE session_revoke_confirmations
                         SET created_at = clock_timestamp() - INTERVAL '2 seconds',
                             expires_at = clock_timestamp() - INTERVAL '1 second'
                         WHERE token_hash = ?
                         """)) {
        expire.setBytes(1, sessionTokenHash(confirmationToken));
        assertEquals(1, expire.executeUpdate());
      }

      MvcResult response =
          mockMvc
              .perform(
                  post("/api/v1/auth/logout-all")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsString(
                              Map.of("confirmationToken", confirmationToken)))
                      .header("X-CSRF-Token", csrfToken)
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", session.cookieValue())))
              .andReturn();
      assertEquals(403, response.getResponse().getStatus());
      assertTrue(
          response.getResponse().getContentAsString().contains("LOGOUT_ALL_CONFIRMATION_INVALID"));
      assertNull(response.getResponse().getHeader("Set-Cookie"));
      assertEquals(1, activeSessionCount(userId));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void concurrentLogoutAllRequestsConsumeConfirmationOnlyOnce() throws Exception {
    UUID userId = createSessionTestUser("Concurrent Logout All Reader");
    int requestCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(requestCount);
    CountDownLatch ready = new CountDownLatch(requestCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      SessionService.IssuedSession current = sessionService.create(userId).orElseThrow();
      sessionService.create(userId).orElseThrow();
      LogoutAllConfirmationService.IssuedConfirmation confirmation =
          logoutAllConfirmationService.issue(userId, current.cookieValue()).orElseThrow();
      for (int index = 0; index < requestCount; index++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent logout-all test did not start");
                  }
                  return logoutAllConfirmationService.consumeAndRevokeAll(
                      userId, current.cookieValue(), confirmation.token());
                }));
      }

      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      int winners = 0;
      for (Future<Boolean> result : results) {
        winners += result.get(20, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertEquals(1, winners);
      assertEquals(0, activeSessionCount(userId));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void googleBootstrapHttpEndpointStopsAtTenAttemptsPerClientAddress() throws Exception {
    String clientAddress = bootstrapTestClientAddress();
    List<UUID> correlationIds = new ArrayList<>();
    try {
      for (int index = 0; index < 11; index++) {
        MvcResult result =
            mockMvc
                .perform(
                    get("/api/v1/auth/google/bootstrap")
                        .with(
                            request -> {
                              request.setRemoteAddr(clientAddress);
                              return request;
                            }))
                .andReturn();
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
        correlationIds.add(correlationId);

        if (index < 10) {
          assertEquals(200, result.getResponse().getStatus());
          assertEquals(1, googleAuthAttemptCount(correlationId));
        } else {
          assertEquals(429, result.getResponse().getStatus());
          assertEquals(0, googleAuthAttemptCount(correlationId));
          assertTrue(
              objectMapper
                  .readTree(result.getResponse().getContentAsString())
                  .path("detail")
                  .asText()
                  .contains("wait"));
        }
      }
    } finally {
      deleteBootstrapFixtures(clientAddress, correlationIds);
    }
  }

  @Test
  void googleBootstrapHttpEndpointUsesForwardedAddressOnlyFromTrustedProxies() throws Exception {
    String clientAddress = bootstrapTestClientAddress();
    List<UUID> correlationIds = new ArrayList<>();
    try {
      for (int index = 0; index < 11; index++) {
        String proxyAddress = index % 2 == 0 ? "10.1.0.10" : "10.1.0.11";
        MvcResult result =
            mockMvc
                .perform(
                    get("/api/v1/auth/google/bootstrap")
                        .header("X-Forwarded-For", clientAddress)
                        .with(
                            request -> {
                              request.setRemoteAddr(proxyAddress);
                              return request;
                            }))
                .andReturn();
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
        correlationIds.add(correlationId);
        assertEquals(index < 10 ? 200 : 429, result.getResponse().getStatus());
      }
    } finally {
      deleteBootstrapFixtures(clientAddress, correlationIds);
    }
  }

  @Test
  void googleBootstrapHttpEndpointIgnoresSpoofedForwardedAddressFromUntrustedPeer()
      throws Exception {
    String clientAddress = bootstrapTestClientAddress();
    List<UUID> correlationIds = new ArrayList<>();
    try {
      for (int index = 0; index < 11; index++) {
        MvcResult result =
            mockMvc
                .perform(
                    get("/api/v1/auth/google/bootstrap")
                        .header("X-Forwarded-For", "198.51.100." + (index + 1))
                        .with(
                            request -> {
                              request.setRemoteAddr(clientAddress);
                              return request;
                            }))
                .andReturn();
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
        correlationIds.add(correlationId);
        assertEquals(index < 10 ? 200 : 429, result.getResponse().getStatus());
      }
    } finally {
      deleteBootstrapFixtures(clientAddress, correlationIds);
    }
  }

  @Test
  void concurrentGoogleAuthBootstrapRequestsCannotExceedBucketCapacity() throws Exception {
    byte[] rateLimitKeyHash = sha256(UUID.randomUUID().toString());
    int requests = 20;
    ExecutorService executor = Executors.newFixedThreadPool(requests);
    CountDownLatch ready = new CountDownLatch(requests);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();

    try {
      for (int index = 0; index < requests; index++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("rate-limit consumer start signal timed out");
                  }
                  return googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash);
                }));
      }

      assertTrue(
          ready.await(5, TimeUnit.SECONDS),
          "all rate-limit requests should reach the start barrier");
      start.countDown();
      int winners = 0;
      for (Future<Boolean> result : results) {
        if (result.get(15, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertEquals(
          10, winners, "the locked PostgreSQL token bucket should admit exactly its capacity");
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      deleteRateLimitBucket(rateLimitKeyHash);
    }
  }

  @Test
  void inactiveGoogleAuthRateLimitBucketsCanBePurged() throws Exception {
    byte[] staleKeyHash = sha256(UUID.randomUUID().toString());
    byte[] activeKeyHash = sha256(UUID.randomUUID().toString());
    try {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement insert =
              connection.prepareStatement(
                  """
                         INSERT INTO google_auth_bootstrap_rate_limits (
                             rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                         VALUES (?, 0, clock_timestamp(), clock_timestamp() - INTERVAL '25 hours'),
                                (?, 4, clock_timestamp(), clock_timestamp())
                         """)) {
        insert.setBytes(1, staleKeyHash);
        insert.setBytes(2, activeKeyHash);
        assertEquals(2, insert.executeUpdate());
      }

      assertEquals(1, googleAuthBootstrapRateLimiter.purgeInactiveBuckets());
      assertEquals(0, rateLimitBucketCount(staleKeyHash));
      assertEquals(1, rateLimitBucketCount(activeKeyHash));
    } finally {
      deleteRateLimitBucket(staleKeyHash);
      deleteRateLimitBucket(activeKeyHash);
    }
  }

  @Test
  void firstGoogleSignInCreatesUserAndCanonicalProviderIdentityAtomically() throws Exception {
    String subject = "first-" + UUID.randomUUID();
    String email = subject + "@example.test";
    GoogleIdTokenVerifier.VerifiedGoogleIdentity identity =
        verifiedIdentity(subject, email, "First Reader");
    GoogleIdentityService.IdentityAccount account = null;

    try {
      account = googleIdentityService.findOrCreate(identity);

      assertTrue(account.created());
      assertEquals("active", account.status());
      assertEquals("First Reader", account.displayName());
      assertEquals(email, account.contactEmail());
      assertEquals(1, identityCount(subject));
      assertEquals(1, userCount(account.userId()));
      assertEquals("https://accounts.google.com", identityIssuer(subject));
    } finally {
      if (account != null) {
        deleteMappedIdentity(account.userId(), subject);
      }
    }
  }

  @Test
  void returningGoogleIdentityUpdatesVerifiedEmailWithoutCreatingAnotherUser() throws Exception {
    String subject = "returning-" + UUID.randomUUID();
    String firstEmail = subject + "@old.example.test";
    String updatedEmail = subject + "@new.example.test";
    GoogleIdentityService.IdentityAccount first = null;

    try {
      first = googleIdentityService.findOrCreate(verifiedIdentity(subject, firstEmail, "Old Name"));
      GoogleIdentityService.IdentityAccount returned =
          googleIdentityService.findOrCreate(
              verifiedIdentity(subject, updatedEmail, "Updated Name"));

      assertFalse(returned.created());
      assertEquals(first.userId(), returned.userId());
      assertEquals(updatedEmail, returned.contactEmail());
      assertEquals("Updated Name", returned.displayName());
      assertEquals(1, identityCount(subject));
      assertEquals(1, userCount(first.userId()));
      assertEquals(updatedEmail, storedProviderEmail(subject));
      assertEquals(updatedEmail, storedUserEmail(first.userId()));
      assertTrue(providerIdentityLastSeen(subject));
    } finally {
      if (first != null) {
        deleteMappedIdentity(first.userId(), subject);
      }
    }
  }

  @Test
  void verifiedEmailCollisionRequiresAccountLinkWithoutCreatingOrLinking() throws Exception {
    String subject = "collision-" + UUID.randomUUID();
    String existingEmail = subject + "@example.test";
    try (Connection connection = dataSource.getConnection()) {
      insertUser(connection, "Existing Reader", existingEmail, "active", false);
    }

    try {
      GoogleAccountLinkRequiredException failure =
          assertThrows(
              GoogleAccountLinkRequiredException.class,
              () ->
                  googleIdentityService.findOrCreate(
                      verifiedIdentity(subject, existingEmail, "New Reader")));

      assertEquals(GoogleAccountLinkRequiredException.CODE, failure.code());
      assertFalse(failure.getMessage().contains(existingEmail));
      assertEquals(0, identityCount(subject));
      assertEquals(1, userCountByEmail(existingEmail));
    } finally {
      deleteUserByEmail(existingEmail);
    }
  }

  @Test
  void returningIdentityEmailCollisionRollsBackBothProfileAndProviderClaimUpdates()
      throws Exception {
    String subject = "return-collision-" + UUID.randomUUID();
    String originalEmail = subject + "@original.example.test";
    String collidingEmail = subject + "@claimed.example.test";
    GoogleIdentityService.IdentityAccount account =
        googleIdentityService.findOrCreate(
            verifiedIdentity(subject, originalEmail, "Original Name"));
    try (Connection connection = dataSource.getConnection()) {
      insertUser(connection, "Other Reader", collidingEmail, "active", false);
    }

    try {
      GoogleAccountLinkRequiredException failure =
          assertThrows(
              GoogleAccountLinkRequiredException.class,
              () ->
                  googleIdentityService.findOrCreate(
                      verifiedIdentity(subject, collidingEmail, "Changed Name")));

      assertEquals(GoogleAccountLinkRequiredException.CODE, failure.code());
      assertEquals(originalEmail, storedUserEmail(account.userId()));
      assertEquals("Original Name", storedUserDisplayName(account.userId()));
      assertEquals(originalEmail, storedProviderEmail(subject));
      assertEquals(1, identityCount(subject));
      assertEquals(1, userCountByEmail(collidingEmail));
    } finally {
      deleteMappedIdentity(account.userId(), subject);
      deleteUserByEmail(collidingEmail);
    }
  }

  @Test
  void concurrentFirstSignInsForOneSubjectResolveToOneInternalUser() throws Exception {
    String subject = "concurrent-" + UUID.randomUUID();
    String email = subject + "@example.test";
    GoogleIdTokenVerifier.VerifiedGoogleIdentity identity =
        verifiedIdentity(subject, email, "Concurrent Reader");
    int consumers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(consumers);
    CountDownLatch ready = new CountDownLatch(consumers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<GoogleIdentityService.IdentityAccount>> results = new ArrayList<>();

    try {
      for (int index = 0; index < consumers; index++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "identity consumers did not receive the start signal");
                  }
                  return googleIdentityService.findOrCreate(identity);
                }));
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      UUID userId = null;
      int createdCount = 0;
      for (Future<GoogleIdentityService.IdentityAccount> result : results) {
        GoogleIdentityService.IdentityAccount account = result.get(15, TimeUnit.SECONDS);
        userId = account.userId();
        if (account.created()) {
          createdCount++;
        }
      }
      assertEquals(1, createdCount);
      assertEquals(1, identityCount(subject));
      assertEquals(1, userCount(userId));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      deleteMappedIdentityBySubject(subject);
    }
  }

  @Test
  void suspendedGoogleAccountCannotBeReactivatedBySignIn() throws Exception {
    String subject = "suspended-" + UUID.randomUUID();
    String email = subject + "@example.test";
    UUID userId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      insertUser(connection, "Suspended Reader", email, "suspended", false);
      try (PreparedStatement find =
          connection.prepareStatement("SELECT id FROM users WHERE contact_email = ?")) {
        find.setString(1, email);
        try (ResultSet row = find.executeQuery()) {
          assertTrue(row.next());
          userId = row.getObject(1, UUID.class);
        }
      }
      insertGoogleIdentity(connection, userId, subject, email);
    }

    try {
      assertThrows(
          UnavailableGoogleIdentityException.class,
          () ->
              googleIdentityService.findOrCreate(verifiedIdentity(subject, email, "Changed Name")));
      assertEquals("suspended", storedUserStatus(userId));
      assertEquals("Suspended Reader", storedUserDisplayName(userId));
    } finally {
      deleteMappedIdentity(userId, subject);
    }
  }

  @Test
  void sessionCreationPersistsOnlyTheHashAndValidatesAnActiveUser() throws Exception {
    UUID userId = createSessionTestUser("Session Reader");
    try {
      SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
      assertEquals(userId, issued.userId());
      assertEquals(43, issued.cookieValue().length());
      assertTrue(sessionHashMatches(issued.cookieValue(), userId));
      assertFalse(
          Arrays.equals(
              issued.cookieValue().getBytes(StandardCharsets.UTF_8),
              sessionTokenHash(issued.cookieValue())));

      SessionService.SessionPrincipal principal =
          sessionService.validateAndTouch(issued.cookieValue()).orElseThrow();
      assertEquals(userId, principal.userId());
      assertEquals(issued.expiresAt(), principal.expiresAt());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void malformedTokensAreRejectedAndRevokeIsIdempotent() throws Exception {
    UUID userId = createSessionTestUser("Revoke Reader");
    try {
      SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
      assertTrue(sessionService.validateAndTouch("not-a-session").isEmpty());
      assertFalse(sessionService.revoke("not-a-session"));

      String tampered =
          (issued.cookieValue().charAt(0) == 'A' ? "B" : "A") + issued.cookieValue().substring(1);
      assertTrue(sessionService.validateAndTouch(tampered).isEmpty());

      assertTrue(sessionService.revoke(issued.cookieValue()));
      assertFalse(sessionService.revoke(issued.cookieValue()));
      assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isEmpty());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void idleAndAbsoluteExpirationBothRejectTheSession() throws Exception {
    UUID userId = createSessionTestUser("Expired Session Reader");
    try {
      SessionService.IssuedSession idleExpired = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession absoluteExpired = sessionService.create(userId).orElseThrow();
      try (Connection connection = dataSource.getConnection();
          PreparedStatement expire =
              connection.prepareStatement(
                  """
                         UPDATE sessions
                         SET created_at = CASE WHEN id_hash = ?
                                               THEN clock_timestamp() - INTERVAL '9 days'
                                               ELSE clock_timestamp() - INTERVAL '31 days' END,
                             last_seen_at = CASE WHEN id_hash = ?
                                                 THEN clock_timestamp() - INTERVAL '8 days'
                                                 ELSE clock_timestamp() - INTERVAL '1 day' END,
                             expires_at = CASE WHEN id_hash = ?
                                               THEN clock_timestamp() + INTERVAL '20 days'
                                               ELSE clock_timestamp() - INTERVAL '1 second' END
                         WHERE user_id = ?
                         """)) {
        expire.setBytes(1, sessionTokenHash(idleExpired.cookieValue()));
        expire.setBytes(2, sessionTokenHash(idleExpired.cookieValue()));
        expire.setBytes(3, sessionTokenHash(idleExpired.cookieValue()));
        expire.setObject(4, userId);
        assertEquals(2, expire.executeUpdate());
      }

      assertTrue(sessionService.validateAndTouch(idleExpired.cookieValue()).isEmpty());
      assertTrue(sessionService.validateAndTouch(absoluteExpired.cookieValue()).isEmpty());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void activityTouchesAfterThrottleIntervalButDoesNotWriteOnEveryRequest() throws Exception {
    UUID userId = createSessionTestUser("Active Session Reader");
    try {
      SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
      byte[] tokenHash = sessionTokenHash(issued.cookieValue());
      try (Connection connection = dataSource.getConnection();
          PreparedStatement age =
              connection.prepareStatement(
                  """
                         UPDATE sessions
                         SET created_at = clock_timestamp() - INTERVAL '1 day',
                             last_seen_at = clock_timestamp() - INTERVAL '10 minutes',
                             expires_at = clock_timestamp() + INTERVAL '29 days'
                         WHERE id_hash = ?
                         """)) {
        age.setBytes(1, tokenHash);
        assertEquals(1, age.executeUpdate());
      }

      Instant agedLastSeen = storedSessionLastSeen(tokenHash);
      assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isPresent());
      Instant touchedLastSeen = storedSessionLastSeen(tokenHash);
      assertTrue(touchedLastSeen.isAfter(agedLastSeen));

      assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isPresent());
      assertEquals(touchedLastSeen, storedSessionLastSeen(tokenHash));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void rotationRevokesOldSecretAndRevokeAllInvalidatesReplacement() throws Exception {
    UUID userId = createSessionTestUser("Rotating Session Reader");
    try {
      SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession rotated =
          sessionService.rotate(original.cookieValue()).orElseThrow();

      assertNotEquals(original.cookieValue(), rotated.cookieValue());
      assertEquals(
          original.expiresAt(), rotated.expiresAt(), "rotation must preserve absolute expiry");
      assertTrue(sessionService.validateAndTouch(original.cookieValue()).isEmpty());
      assertEquals(
          userId, sessionService.validateAndTouch(rotated.cookieValue()).orElseThrow().userId());
      assertEquals(1, activeSessionCount(userId));
      assertEquals(1, sessionService.revokeAll(userId));
      assertEquals(0, sessionService.revokeAll(userId));
      assertTrue(sessionService.validateAndTouch(rotated.cookieValue()).isEmpty());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void concurrentRotationOfOneSecretHasExactlyOneWinner() throws Exception {
    UUID userId = createSessionTestUser("Concurrent Session Reader");
    int consumers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(consumers);
    CountDownLatch ready = new CountDownLatch(consumers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Optional<SessionService.IssuedSession>>> results = new ArrayList<>();

    try {
      SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
      for (int index = 0; index < consumers; index++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "session rotation requests did not reach the barrier");
                  }
                  return sessionService.rotate(original.cookieValue());
                }));
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      SessionService.IssuedSession winner = null;
      int winnerCount = 0;
      for (Future<Optional<SessionService.IssuedSession>> result : results) {
        Optional<SessionService.IssuedSession> rotated = result.get(15, TimeUnit.SECONDS);
        if (rotated.isPresent()) {
          winner = rotated.orElseThrow();
          winnerCount++;
        }
      }
      assertEquals(1, winnerCount);
      assertTrue(sessionService.validateAndTouch(original.cookieValue()).isEmpty());
      assertEquals(
          userId, sessionService.validateAndTouch(winner.cookieValue()).orElseThrow().userId());
      assertEquals(1, activeSessionCount(userId));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void suspendedUsersCannotCreateOrContinueASession() throws Exception {
    UUID userId = createSessionTestUser("Suspended Session Reader");
    try {
      SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
      try (Connection connection = dataSource.getConnection();
          PreparedStatement suspend =
              connection.prepareStatement("UPDATE users SET status = 'suspended' WHERE id = ?")) {
        suspend.setObject(1, userId);
        assertEquals(1, suspend.executeUpdate());
      }

      assertTrue(sessionService.create(userId).isEmpty());
      assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isEmpty());
      assertTrue(sessionService.rotate(issued.cookieValue()).isEmpty());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void creditLedgerIsAppendOnlyAndItsBalanceProjectionCanBeRebuilt() throws Exception {
    UUID userId = createSessionTestUser("Commerce Ledger Owner");
    UUID paymentOrderId = UUID.randomUUID();
    UUID unlockId = UUID.randomUUID();
    UUID paidGrantId = null;
    try {
      paidGrantId =
          insertCreditLedgerEntry(
              userId, "TOP_UP", "PAID", 100, null, "PAYMENT_ORDER", paymentOrderId, null);
      insertCreditLedgerEntry(
          userId,
          "BONUS_GRANT",
          "BONUS",
          20,
          Instant.now().plusSeconds(3_600),
          "PAYMENT_ORDER",
          paymentOrderId,
          null);
      insertCreditLedgerEntry(
          userId,
          "BONUS_GRANT",
          "BONUS",
          50,
          Instant.now().minusSeconds(60),
          "PAYMENT_ORDER",
          paymentOrderId,
          null);

      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(20, creditProjectedBalance(userId, "BONUS"));

      UUID immutableGrantId = paidGrantId;
      assertThrows(SQLException.class, () -> updateCreditLedgerEntry(immutableGrantId));
      assertThrows(SQLException.class, () -> deleteCreditLedgerEntry(immutableGrantId));
      assertThrows(
          SQLException.class,
          () ->
              insertCreditLedgerEntry(
                  userId, "TOP_UP", "PAID", 100, null, "PAYMENT_ORDER", paymentOrderId, null));

      insertCreditLedgerEntry(
          userId, "UNLOCK_DEBIT", "PAID", -60, null, "CHAPTER_UNLOCK", unlockId, null);
      assertEquals(40, creditProjectedBalance(userId, "PAID"));
      assertThrows(
          SQLException.class,
          () ->
              insertCreditLedgerEntry(
                  userId,
                  "UNLOCK_DEBIT",
                  "PAID",
                  -41,
                  null,
                  "CHAPTER_UNLOCK",
                  UUID.randomUUID(),
                  null));
      assertEquals(40, creditProjectedBalance(userId, "PAID"));

      UUID reversalId = UUID.randomUUID();
      insertCreditLedgerEntry(
          userId, "REVERSAL", "PAID", -10, null, "REVERSAL", reversalId, paidGrantId);
      assertEquals(30, creditProjectedBalance(userId, "PAID"));
      assertThrows(
          SQLException.class,
          () ->
              insertCreditLedgerEntry(
                  userId,
                  "REVERSAL",
                  "PAID",
                  -10,
                  null,
                  "REVERSAL",
                  UUID.randomUUID(),
                  immutableGrantId));

      try (Connection connection = dataSource.getConnection();
          PreparedStatement rebuild =
              connection.prepareStatement("SELECT rebuild_credit_balance_projection(?)")) {
        rebuild.setObject(1, userId);
        rebuild.execute();
      }
      assertEquals(30, creditProjectedBalance(userId, "PAID"));
      assertEquals(20, creditProjectedBalance(userId, "BONUS"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void creditLedgerServiceMakesPaidGrantsDebitsAndReversalsIdempotent() throws Exception {
    UUID userId = createSessionTestUser("Credit Ledger Service Owner");
    UUID otherUserId = createSessionTestUser("Credit Ledger Service Other Owner");
    UUID paymentOrderId = UUID.randomUUID();
    UUID unlockId = UUID.randomUUID();
    try {
      CreditLedgerService.OperationResult grant =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.grantPaidTopUp(connection, userId, paymentOrderId, 100));
      assertEquals(CreditLedgerService.Outcome.APPLIED, grant.outcome());
      assertEquals(1, grant.ledgerEntryIds().size());

      CreditLedgerService.OperationResult grantReplay =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.grantPaidTopUp(connection, userId, paymentOrderId, 100));
      assertEquals(CreditLedgerService.Outcome.ALREADY_APPLIED, grantReplay.outcome());
      assertEquals(grant.ledgerEntryIds(), grantReplay.ledgerEntryIds());
      assertEquals(
          CreditLedgerService.Outcome.SOURCE_OPERATION_REUSED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(connection, userId, paymentOrderId, 101))
              .outcome());
      assertEquals(
          CreditLedgerService.Outcome.SOURCE_OPERATION_REUSED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(
                          connection, otherUserId, paymentOrderId, 100))
              .outcome());

      List<CreditLedgerService.Allocation> paidTen =
          List.of(new CreditLedgerService.Allocation(CreditLedgerService.Bucket.PAID, 10, null));
      assertEquals(
          CreditLedgerService.Outcome.INSUFFICIENT_CREDITS,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.debitUnlock(
                          connection,
                          userId,
                          unlockId,
                          101,
                          List.of(
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.PAID, 101, null))))
              .outcome());
      assertEquals(0, creditSourceEntryCount("CHAPTER_UNLOCK", unlockId));

      CreditLedgerService.OperationResult debit =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.debitUnlock(connection, userId, unlockId, 10, paidTen));
      assertEquals(CreditLedgerService.Outcome.APPLIED, debit.outcome());
      assertEquals(-10, debit.amountDelta());
      CreditLedgerService.OperationResult debitReplay =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.debitUnlock(connection, userId, unlockId, 10, paidTen));
      assertEquals(CreditLedgerService.Outcome.ALREADY_APPLIED, debitReplay.outcome());
      assertEquals(debit.ledgerEntryIds(), debitReplay.ledgerEntryIds());
      assertEquals(
          CreditLedgerService.Outcome.SOURCE_OPERATION_REUSED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.debitUnlock(
                          connection,
                          userId,
                          unlockId,
                          11,
                          List.of(
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.PAID, 11, null))))
              .outcome());
      assertEquals(90, creditProjectedBalance(userId, "PAID"));

      UUID grantEntryId = grant.ledgerEntryIds().getFirst();
      assertEquals(
          CreditLedgerService.Outcome.REVERSAL_AMOUNT_EXCEEDS_SOURCE,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.reverseEntry(connection, userId, grantEntryId, 101))
              .outcome());
      assertEquals(
          CreditLedgerService.Outcome.BALANCE_CONFLICT,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.reverseEntry(connection, userId, grantEntryId, 95))
              .outcome());
      assertEquals(90, creditProjectedBalance(userId, "PAID"));

      CreditLedgerService.OperationResult reversal =
          inCreditLedgerTransaction(
              connection -> creditLedgerService.reverseEntry(connection, userId, grantEntryId, 50));
      assertEquals(CreditLedgerService.Outcome.APPLIED, reversal.outcome());
      assertEquals(-50, reversal.amountDelta());
      CreditLedgerService.OperationResult reversalReplay =
          inCreditLedgerTransaction(
              connection -> creditLedgerService.reverseEntry(connection, userId, grantEntryId, 50));
      assertEquals(CreditLedgerService.Outcome.ALREADY_APPLIED, reversalReplay.outcome());
      assertEquals(reversal.ledgerEntryIds(), reversalReplay.ledgerEntryIds());
      assertEquals(
          CreditLedgerService.Outcome.ENTRY_ALREADY_REVERSED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.reverseEntry(connection, userId, grantEntryId, 40))
              .outcome());

      CreditLedgerService.OperationResult debitReversal =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.reverseEntry(
                      connection, userId, debit.ledgerEntryIds().getFirst(), 10));
      assertEquals(CreditLedgerService.Outcome.APPLIED, debitReversal.outcome());
      assertEquals(50, creditProjectedBalance(userId, "PAID"));
      assertEquals(
          CreditLedgerService.Outcome.ENTRY_NOT_FOUND,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.reverseEntry(connection, otherUserId, grantEntryId, 10))
              .outcome());
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void creditLedgerServiceRejectsExpiredAllocationsWithoutPartiallyDebiting() throws Exception {
    UUID userId = createSessionTestUser("Credit Ledger Expiry Owner");
    UUID paymentOrderId = UUID.randomUUID();
    UUID liveBonusSource = UUID.randomUUID();
    UUID expiredBonusSource = UUID.randomUUID();
    UUID mixedUnlockId = UUID.randomUUID();
    UUID expiredUnlockId = UUID.randomUUID();
    try {
      assertEquals(
          CreditLedgerService.Outcome.APPLIED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(connection, userId, paymentOrderId, 20))
              .outcome());
      Instant liveExpiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
      Instant expiredAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
      insertCreditLedgerEntry(
          userId, "BONUS_GRANT", "BONUS", 15, liveExpiry, "OPERATOR", liveBonusSource, null);
      insertCreditLedgerEntry(
          userId, "BONUS_GRANT", "BONUS", 5, expiredAt, "OPERATOR", expiredBonusSource, null);

      List<CreditLedgerService.Allocation> allocations =
          List.of(
              new CreditLedgerService.Allocation(CreditLedgerService.Bucket.PAID, 10, null),
              new CreditLedgerService.Allocation(CreditLedgerService.Bucket.BONUS, 10, liveExpiry));
      CreditLedgerService.OperationResult mixedDebit =
          inCreditLedgerTransaction(
              connection ->
                  creditLedgerService.debitUnlock(
                      connection, userId, mixedUnlockId, 20, allocations));
      assertEquals(CreditLedgerService.Outcome.APPLIED, mixedDebit.outcome());
      assertEquals(10, creditProjectedBalance(userId, "PAID"));
      assertEquals(5, creditProjectedBalance(userId, "BONUS"));

      assertEquals(
          CreditLedgerService.Outcome.INSUFFICIENT_CREDITS,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.debitUnlock(
                          connection,
                          userId,
                          expiredUnlockId,
                          1,
                          List.of(
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.BONUS, 1, expiredAt))))
              .outcome());

      UUID secondMixedUnlockId = UUID.randomUUID();
      assertEquals(
          CreditLedgerService.Outcome.INSUFFICIENT_CREDITS,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.debitUnlock(
                          connection,
                          userId,
                          secondMixedUnlockId,
                          11,
                          List.of(
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.PAID, 10, null),
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.BONUS, 1, expiredAt))))
              .outcome());
      assertEquals(0, creditSourceEntryCount("CHAPTER_UNLOCK", secondMixedUnlockId));
      assertEquals(10, creditProjectedBalance(userId, "PAID"));
      assertEquals(5, creditProjectedBalance(userId, "BONUS"));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void creditLedgerServiceSerializesCompetingUnlockDebits() throws Exception {
    UUID userId = createSessionTestUser("Credit Ledger Concurrent Owner");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      assertEquals(
          CreditLedgerService.Outcome.APPLIED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(connection, userId, UUID.randomUUID(), 10))
              .outcome());

      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<CreditLedgerService.Outcome>> attempts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        UUID unlockId = UUID.randomUUID();
        attempts.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("credit debit race did not start");
                  }
                  return inCreditLedgerTransaction(
                          connection ->
                              creditLedgerService.debitUnlock(
                                  connection,
                                  userId,
                                  unlockId,
                                  10,
                                  List.of(
                                      new CreditLedgerService.Allocation(
                                          CreditLedgerService.Bucket.PAID, 10, null))))
                      .outcome();
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<CreditLedgerService.Outcome> outcomes =
          List.of(
              attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS));
      assertEquals(
          1,
          outcomes.stream().filter(value -> value == CreditLedgerService.Outcome.APPLIED).count());
      assertEquals(
          1,
          outcomes.stream()
              .filter(value -> value == CreditLedgerService.Outcome.INSUFFICIENT_CREDITS)
              .count());
      assertEquals(0, creditProjectedBalance(userId, "PAID"));
    } finally {
      executor.shutdownNow();
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void creditTopUpOrdersPersistTheServerQuoteAndReplayOnlyForTheirActor() throws Exception {
    UUID userId = createSessionTestUser("Top-up Order Owner");
    UUID otherUserId = createSessionTestUser("Other Top-up Order Owner");
    String idempotencyKey = "credit-top-up-order-owner-0001";
    CreditPackCatalog catalog = CreditPackCatalog.momoTestFixture();
    try {
      CreditTopUpOrderService.CreateResult created =
          creditTopUpOrderService.createOrReplay(
              userId, idempotencyKey, catalog, "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.CREATED, created.outcome());
      CreditTopUpOrderService.TopUpOrder order = created.order();
      assertNotNull(order);
      assertEquals(userId, order.userId());
      assertEquals(CreditTopUpOrderService.Provider.MOMO, order.provider());
      assertEquals(CreditTopUpOrderService.OrderStatus.CREATED, order.status());
      assertEquals(1_000, order.quote().amountVnd());
      assertEquals("VND", order.quote().currency());
      assertEquals(CreditPackCatalog.PaymentEnvironment.TEST, order.quote().environment());
      assertEquals(100, order.quote().paidCredits());
      assertEquals(0, order.quote().bonusCredits());
      assertNull(order.quote().paidExpiresAt());
      assertNull(order.quote().bonusExpiresAt());

      CreditTopUpOrderService.CreateResult replay =
          creditTopUpOrderService.createOrReplay(
              userId, idempotencyKey, catalog, "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.REPLAYED, replay.outcome());
      assertEquals(order.id(), replay.order().id());
      assertEquals(order.requestId(), replay.order().requestId());
      assertEquals(
          CreditTopUpOrderService.Outcome.IDEMPOTENCY_KEY_REUSED,
          creditTopUpOrderService
              .createOrReplay(userId, idempotencyKey, catalog, "momo-test-credits-100", 2)
              .outcome());
      assertEquals(
          CreditTopUpOrderService.Outcome.PACK_VERSION_STALE,
          creditTopUpOrderService
              .createOrReplay(
                  userId,
                  "credit-top-up-order-stale-version-01",
                  catalog,
                  "momo-test-credits-100",
                  2)
              .outcome());
      assertTrue(creditTopUpOrderService.findForActor(userId, order.id()).isPresent());
      assertTrue(creditTopUpOrderService.findForActor(otherUserId, order.id()).isEmpty());

      assertEquals(
          CreditTopUpOrderService.Outcome.IDEMPOTENCY_KEY_REUSED,
          creditTopUpOrderService
              .createOrReplay(userId, idempotencyKey, catalog, "unknown-pack")
              .outcome());
      assertEquals(
          CreditTopUpOrderService.Outcome.PACK_NOT_FOUND,
          creditTopUpOrderService
              .createOrReplay(userId, "credit-top-up-order-unknown-0001", catalog, "unknown-pack")
              .outcome());
      assertEquals(
          CreditTopUpOrderService.Outcome.INVALID_IDEMPOTENCY_KEY,
          creditTopUpOrderService
              .createOrReplay(userId, "short", catalog, "momo-test-credits-100")
              .outcome());

      CreditTopUpOrderService.CreateResult otherActorOrder =
          creditTopUpOrderService.createOrReplay(
              otherUserId, idempotencyKey, catalog, "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.CREATED, otherActorOrder.outcome());
      assertNotEquals(order.id(), otherActorOrder.order().id());
      assertEquals(1, creditTopUpOrderCount(userId));
      assertEquals(1, creditTopUpOrderCount(otherUserId));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", order.id()));
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void topUpCatalogAndCheckoutRoutesRequirePolicyCsrfAndUseOnlyServerPackValues() throws Exception {
    UUID userId = createSessionTestUser("HTTP TEST Top-up Owner");
    try {
      addCurrentPolicyAcceptances(userId);
      SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
      jakarta.servlet.http.Cookie cookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue());
      String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
      String idempotencyKey = "credit-top-up-http-route-01";
      String requestBody =
          objectMapper.writeValueAsString(
              Map.of("packId", "momo-test-credits-100", "packVersion", 1));

      MvcResult catalog = mockMvc.perform(get("/api/v1/credit-packs").cookie(cookie)).andReturn();
      assertEquals(200, catalog.getResponse().getStatus());
      assertTrue(catalog.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(catalog.getResponse().getContentAsString().contains("\"amountVnd\":1000"));
      assertTrue(catalog.getResponse().getContentAsString().contains("\"paidCredits\":100"));
      assertTrue(catalog.getResponse().getContentAsString().contains("\"environment\":\"TEST\""));

      MvcResult csrfRejected =
          mockMvc
              .perform(
                  post("/api/v1/momo/payment-orders")
                      .cookie(cookie)
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody))
              .andReturn();
      assertEquals(403, csrfRejected.getResponse().getStatus());
      assertTrue(csrfRejected.getResponse().getContentAsString().contains("CSRF_FAILED"));
      verifyNoInteractions(creditTopUpCheckoutService);

      CreditPackCatalog.CreditPackQuote quote =
          CreditPackCatalog.momoTestFixture().quote("momo-test-credits-100").orElseThrow();
      UUID orderId = UUID.randomUUID();
      CreditTopUpOrderService.TopUpOrder order =
          new CreditTopUpOrderService.TopUpOrder(
              orderId,
              userId,
              UUID.randomUUID(),
              CreditTopUpOrderService.Provider.MOMO,
              quote,
              CreditTopUpOrderService.OrderStatus.PENDING,
              Instant.now());
      String checkoutUrl = "https://test-payment.momo.vn/v2/gateway/pay?token=synthetic";
      when(creditTopUpCheckoutService.createOrReplay(
              eq(userId),
              eq(idempotencyKey),
              any(CreditPackCatalog.class),
              eq("momo-test-credits-100"),
              eq(1)))
          .thenReturn(
              new CreditTopUpCheckoutService.CheckoutResult(
                  CreditTopUpOrderService.Outcome.CREATED,
                  CreditTopUpOrderService.CheckoutOutcome.READY,
                  order,
                  checkoutUrl));

      MvcResult checkout =
          mockMvc
              .perform(
                  post("/api/v1/momo/payment-orders")
                      .cookie(cookie)
                      .header("X-CSRF-Token", csrfToken)
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody))
              .andReturn();
      assertEquals(201, checkout.getResponse().getStatus());
      assertEquals(
          "/api/v1/payment-orders/" + orderId,
          checkout.getResponse().getHeader(HttpHeaders.LOCATION));
      assertTrue(checkout.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(checkout.getResponse().getContentAsString().contains(checkoutUrl));
      assertTrue(!checkout.getResponse().getContentAsString().contains(userId.toString()));
      assertTrue(
          !checkout.getResponse().getContentAsString().contains(order.requestId().toString()));
      verify(creditTopUpCheckoutService)
          .createOrReplay(
              eq(userId),
              eq(idempotencyKey),
              any(CreditPackCatalog.class),
              eq("momo-test-credits-100"),
              eq(1));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void paymentOrderStatusRouteIsActorScopedAndNeverReturnsCheckoutUrl() throws Exception {
    UUID ownerId = createSessionTestUser("HTTP TEST Top-up Status Owner");
    UUID foreignId = createSessionTestUser("HTTP TEST Foreign Top-up Status");
    try {
      addCurrentPolicyAcceptances(ownerId);
      addCurrentPolicyAcceptances(foreignId);
      CreditTopUpOrderService.TopUpOrder order =
          creditTopUpOrderService
              .createOrReplay(
                  ownerId,
                  "credit-top-up-status-owner-01",
                  CreditPackCatalog.momoTestFixture(),
                  "momo-test-credits-100")
              .order();
      SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
      SessionService.IssuedSession foreignSession = sessionService.create(foreignId).orElseThrow();

      MvcResult ownerResponse =
          mockMvc
              .perform(
                  get("/api/v1/payment-orders/{orderId}", order.id())
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", ownerSession.cookieValue())))
              .andReturn();
      assertEquals(200, ownerResponse.getResponse().getStatus());
      assertTrue(ownerResponse.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(
          ownerResponse.getResponse().getContentAsString().contains("\"status\":\"CREATED\""));
      assertTrue(!ownerResponse.getResponse().getContentAsString().contains("checkoutUrl"));
      assertTrue(!ownerResponse.getResponse().getContentAsString().contains("requestId"));
      assertTrue(!ownerResponse.getResponse().getContentAsString().contains("userId"));

      MvcResult foreignResponse =
          mockMvc
              .perform(
                  get("/api/v1/payment-orders/{orderId}", order.id())
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", foreignSession.cookieValue())))
              .andReturn();
      assertEquals(404, foreignResponse.getResponse().getStatus());
      assertTrue(
          foreignResponse.getResponse().getContentAsString().contains("PAYMENT_ORDER_NOT_FOUND"));
    } finally {
      deleteSessionTestUser(ownerId);
      deleteSessionTestUser(foreignId);
    }
  }

  @Test
  void publicChapterUnlockQuotesServerPriceDebitsAtomicallyAndEnablesReaderContent()
      throws Exception {
    UUID authorId = createSessionTestUser("Chapter Unlock Author");
    UUID buyerId = createSessionTestUser("Chapter Unlock Buyer");
    UUID underfundedId = createSessionTestUser("Chapter Unlock Underfunded Buyer");
    SyntheticPublicationScopeFixture publicationFixture = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(authorId, "Unlock Fixture Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              authorId,
              library.id(),
              "Public Unlock Fixture",
              "Synthetic Author",
              "vi",
              sha256("chapter-unlock-public-fixture"));
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(authorId, work.id(), 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 2, "Chapter 2", "vi");
      LibraryRepository.ChapterRecord draftChapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 3, "Draft Chapter", "vi");
      libraryService.appendChapterRevision(
          authorId,
          firstChapter.id(),
          "plain_text",
          "The purchased chapter body.",
          List.of(
              new LibraryService.NewSegment(
                  1, "paragraph", "The purchased chapter body.", "c1-s1")));
      libraryService.appendChapterRevision(
          authorId,
          secondChapter.id(),
          "plain_text",
          "The second chapter body.",
          List.of(
              new LibraryService.NewSegment(1, "paragraph", "The second chapter body.", "c2-s1")));
      publicationFixture =
          approveSyntheticPublicationScopes(
              authorId, work.id(), "PUBLIC_METADATA", "CHAPTER_TEXT", "COMMERCIAL_SALE");
      addCurrentPolicyAcceptances(buyerId);
      addCurrentPolicyAcceptances(underfundedId);
      inCreditLedgerTransaction(
          connection ->
              creditLedgerService.grantPaidTopUp(connection, buyerId, UUID.randomUUID(), 100));

      SessionService.IssuedSession buyerSession = sessionService.create(buyerId).orElseThrow();
      jakarta.servlet.http.Cookie buyerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", buyerSession.cookieValue());
      String buyerCsrf = csrfTokenService.tokenFor(buyerSession.cookieValue());
      String unlockKey = "chapter-unlock-http-001";
      String staleKey = "chapter-unlock-stale-001";
      String underfundedKey = "chapter-unlock-empty-001";

      MvcResult quote =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/unlock-quote", firstChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(200, quote.getResponse().getStatus());
      assertTrue(quote.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode quoteBody = objectMapper.readTree(quote.getResponse().getContentAsString());
      assertEquals(1, quoteBody.path("priceVersion").asInt());
      assertEquals(10, quoteBody.path("priceCredits").asInt());
      assertFalse(quoteBody.path("accessAlreadyGranted").asBoolean());

      MvcResult lockedManifest =
          mockMvc
              .perform(get("/api/v1/works/{workId}/reader-manifest", work.id()).cookie(buyerCookie))
              .andReturn();
      assertEquals(200, lockedManifest.getResponse().getStatus());
      JsonNode lockedManifestBody =
          objectMapper.readTree(lockedManifest.getResponse().getContentAsString());
      assertEquals(
          "locked", lockedManifestBody.path("chapters").get(0).path("accessState").asText());
      assertFalse(
          lockedManifest.getResponse().getContentAsString().contains("purchased chapter body"));

      MvcResult deniedContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(404, deniedContent.getResponse().getStatus());
      assertFalse(
          deniedContent.getResponse().getContentAsString().contains("purchased chapter body"));

      MvcResult csrfRejected =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", firstChapter.id())
                      .cookie(buyerCookie)
                      .header("Idempotency-Key", unlockKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(403, csrfRejected.getResponse().getStatus());
      assertEquals(100, creditProjectedBalance(buyerId, "PAID"));

      MvcResult stalePrice =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", firstChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", staleKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":2}"))
              .andReturn();
      assertEquals(409, stalePrice.getResponse().getStatus());
      assertTrue(
          stalePrice.getResponse().getContentAsString().contains("CHAPTER_PRICE_VERSION_STALE"));
      assertEquals(100, creditProjectedBalance(buyerId, "PAID"));

      MvcResult unlocked =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", firstChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", unlockKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1,\"creditsSpent\":1}"))
              .andReturn();
      assertEquals(201, unlocked.getResponse().getStatus());
      assertTrue(unlocked.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode unlockedBody = objectMapper.readTree(unlocked.getResponse().getContentAsString());
      UUID entitlementId = UUID.fromString(unlockedBody.path("entitlementId").asText());
      assertEquals("CREATED", unlockedBody.path("outcome").asText());
      assertEquals(10, unlockedBody.path("creditsSpent").asInt());
      assertFalse(unlocked.getResponse().getContentAsString().contains(buyerId.toString()));
      assertEquals(90, creditProjectedBalance(buyerId, "PAID"));
      assertEquals(1, creditSourceEntryCount("CHAPTER_UNLOCK", entitlementId));

      MvcResult replay =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", firstChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", unlockKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(200, replay.getResponse().getStatus());
      assertEquals("true", replay.getResponse().getHeader("Idempotency-Replayed"));
      assertEquals(90, creditProjectedBalance(buyerId, "PAID"));
      assertEquals(1, creditSourceEntryCount("CHAPTER_UNLOCK", entitlementId));

      MvcResult reusedKey =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", secondChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", unlockKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(409, reusedKey.getResponse().getStatus());
      assertTrue(reusedKey.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      MvcResult alreadyOwned =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", firstChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", "chapter-unlock-again-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(200, alreadyOwned.getResponse().getStatus());
      assertTrue(alreadyOwned.getResponse().getContentAsString().contains("ALREADY_OWNED"));
      assertEquals(90, creditProjectedBalance(buyerId, "PAID"));

      MvcResult readableContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(200, readableContent.getResponse().getStatus());
      assertTrue(
          readableContent.getResponse().getContentAsString().contains("purchased chapter body"));
      JsonNode unlockedManifest =
          objectMapper.readTree(
              mockMvc
                  .perform(
                      get("/api/v1/works/{workId}/reader-manifest", work.id()).cookie(buyerCookie))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertEquals(
          "readable", unlockedManifest.path("chapters").get(0).path("accessState").asText());

      MvcResult draftQuote =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/unlock-quote", draftChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(404, draftQuote.getResponse().getStatus());

      SessionService.IssuedSession underfundedSession =
          sessionService.create(underfundedId).orElseThrow();
      jakarta.servlet.http.Cookie underfundedCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", underfundedSession.cookieValue());
      String underfundedCsrf = csrfTokenService.tokenFor(underfundedSession.cookieValue());
      MvcResult insufficient =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", secondChapter.id())
                      .cookie(underfundedCookie)
                      .header("X-CSRF-Token", underfundedCsrf)
                      .header("Idempotency-Key", underfundedKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(409, insufficient.getResponse().getStatus());
      assertTrue(insufficient.getResponse().getContentAsString().contains("INSUFFICIENT_CREDITS"));
      assertEquals(0, creditProjectedBalance(underfundedId, "PAID"));
      assertEquals(0, creditUnlockEntryCount(underfundedId));
      assertEquals(0, contentEntitlementCount(underfundedId));

      revokeSyntheticPublicationScope(work.id(), publicationFixture, "CHAPTER_TEXT");
      MvcResult revokedContent =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/content", firstChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(404, revokedContent.getResponse().getStatus());
      MvcResult revokedQuote =
          mockMvc
              .perform(
                  get("/api/v1/chapters/{chapterId}/unlock-quote", secondChapter.id())
                      .cookie(buyerCookie))
              .andReturn();
      assertEquals(404, revokedQuote.getResponse().getStatus());
      MvcResult revokedManifest =
          mockMvc
              .perform(get("/api/v1/works/{workId}/reader-manifest", work.id()).cookie(buyerCookie))
              .andReturn();
      JsonNode revokedManifestBody =
          objectMapper.readTree(revokedManifest.getResponse().getContentAsString());
      assertEquals(200, revokedManifest.getResponse().getStatus());
      assertEquals(
          "unavailable", revokedManifestBody.path("chapters").get(0).path("accessState").asText());
      MvcResult revokedUnlock =
          mockMvc
              .perform(
                  post("/api/v1/chapters/{chapterId}/unlock", secondChapter.id())
                      .cookie(buyerCookie)
                      .header("X-CSRF-Token", buyerCsrf)
                      .header("Idempotency-Key", "chapter-unlock-after-revoke-01")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"priceVersion\":1}"))
              .andReturn();
      assertEquals(404, revokedUnlock.getResponse().getStatus());
      assertEquals(90, creditProjectedBalance(buyerId, "PAID"));
    } finally {
      deleteSessionTestUser(buyerId);
      deleteSessionTestUser(underfundedId);
      deleteLibraryTestData(authorId);
      cleanupSyntheticPublicationScopeFixture(publicationFixture);
      deleteSessionTestUser(authorId);
    }
  }

  @Test
  void concurrentChapterUnlocksCreateOneEntitlementAndOnePaidDebit() throws Exception {
    UUID authorId = createSessionTestUser("Concurrent Unlock Author");
    UUID buyerId = createSessionTestUser("Concurrent Unlock Buyer");
    SyntheticPublicationScopeFixture publicationFixture = null;
    try {
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(authorId, "Concurrent Unlock Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              authorId,
              library.id(),
              "Concurrent Public Story",
              "Synthetic Author",
              "vi",
              sha256("concurrent-chapter-unlock"));
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(authorId, work.id(), 1, "Volume 1");
      LibraryRepository.ChapterRecord chapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 1, "Chapter 1", "vi");
      libraryService.appendChapterRevision(
          authorId,
          chapter.id(),
          "plain_text",
          "Concurrent chapter body.",
          List.of(
              new LibraryService.NewSegment(1, "paragraph", "Concurrent chapter body.", "c1-s1")));
      publicationFixture =
          approveSyntheticPublicationScopes(
              authorId, work.id(), "PUBLIC_METADATA", "CHAPTER_TEXT", "COMMERCIAL_SALE");
      inCreditLedgerTransaction(
          connection ->
              creditLedgerService.grantPaidTopUp(connection, buyerId, UUID.randomUUID(), 10));

      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<ChapterUnlockService.UnlockResult> first =
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("start timeout");
                  return chapterUnlockService.unlock(
                      buyerId, chapter.id(), 1, "chapter-unlock-concurrent-001");
                });
        Future<ChapterUnlockService.UnlockResult> second =
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("start timeout");
                  return chapterUnlockService.unlock(
                      buyerId, chapter.id(), 1, "chapter-unlock-concurrent-002");
                });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<ChapterUnlockService.Outcome> outcomes =
            List.of(
                first.get(30, TimeUnit.SECONDS).outcome(),
                second.get(30, TimeUnit.SECONDS).outcome());
        assertTrue(outcomes.contains(ChapterUnlockService.Outcome.CREATED));
        assertTrue(outcomes.contains(ChapterUnlockService.Outcome.ALREADY_OWNED));
        assertEquals(0, creditProjectedBalance(buyerId, "PAID"));
        assertEquals(1, contentEntitlementCount(buyerId));
      } finally {
        executor.shutdownNow();
      }
    } finally {
      deleteSessionTestUser(buyerId);
      deleteLibraryTestData(authorId);
      cleanupSyntheticPublicationScopeFixture(publicationFixture);
      deleteSessionTestUser(authorId);
    }
  }

  @Test
  void creditTopUpCheckoutPersistsOnlyValidatedTestUrlAndSameKeyReplayDoesNotCreateAgain()
      throws Exception {
    UUID userId = createSessionTestUser("Top-up Checkout Owner");
    String idempotencyKey = "credit-top-up-checkout-owner-01";
    String payUrl =
        "HTTPS://TEST-PAYMENT.MOMO.VN:443/v2/gateway/pay?t=synthetic-ticket&s=synthetic-signature";
    AtomicInteger providerCalls = new AtomicInteger();
    CreditTopUpCheckoutService checkout =
        new CreditTopUpCheckoutService(
            creditTopUpOrderService,
            (amountVnd, orderId, requestId) -> {
              assertEquals(1_000, amountVnd);
              assertNotNull(orderId);
              assertNotNull(requestId);
              providerCalls.incrementAndGet();
              return new MomoTestGatewayClient.CreateResult(
                  MomoTestGatewayClient.CreateState.READY,
                  payUrl,
                  MomoTestGatewayClient.FailureCode.NONE);
            });
    try {
      CreditTopUpCheckoutService.CheckoutResult created =
          checkout.createOrReplay(
              userId, idempotencyKey, CreditPackCatalog.momoTestFixture(), "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.CREATED, created.creationOutcome());
      assertEquals(CreditTopUpOrderService.CheckoutOutcome.READY, created.checkoutOutcome());
      assertEquals(CreditTopUpOrderService.OrderStatus.PENDING, created.order().status());
      assertEquals(payUrl, created.checkoutUrl());
      assertFalse(created.toString().contains(payUrl));

      CreditTopUpCheckoutService.CheckoutResult replay =
          checkout.createOrReplay(
              userId, idempotencyKey, CreditPackCatalog.momoTestFixture(), "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.REPLAYED, replay.creationOutcome());
      assertEquals(CreditTopUpOrderService.CheckoutOutcome.READY, replay.checkoutOutcome());
      assertEquals(created.order().id(), replay.order().id());
      assertEquals(payUrl, replay.checkoutUrl());
      assertEquals(1, providerCalls.get());
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", created.order().id()));
      assertEquals(payUrl, storedTopUpCheckoutUrl(userId, created.order().id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void signedMomoTestIpnOverHttpSettlesExactlyOnceAndDoesNotRequireBrowserCsrf() throws Exception {
    UUID userId = createSessionTestUser("MoMo IPN HTTP Owner");
    try {
      CreditTopUpOrderService.TopUpOrder order = createPendingMomoTestTopUp(userId);
      SignedNotification signed =
          MomoTestIpnTestSupport.sign(
              order.id().toString(), order.requestId().toString(), 1_000, 0, 8_765_432_101L);
      byte[] payload = objectMapper.writeValueAsBytes(MomoTestIpnTestSupport.payload(signed));

      var firstResponse =
          mockMvc
              .perform(
                  post("/api/v1/payments/momo/ipn")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(payload))
              .andReturn()
              .getResponse();
      assertEquals(204, firstResponse.getStatus());
      assertEquals("", firstResponse.getContentAsString());
      assertTrue(firstResponse.getHeader("Cache-Control").contains("no-store"));

      var replayResponse =
          mockMvc
              .perform(
                  post("/api/v1/payments/momo/ipn")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(payload))
              .andReturn()
              .getResponse();
      assertEquals(204, replayResponse.getStatus());
      assertEquals(CreditTopUpOrderService.OrderStatus.SUCCEEDED, topUpOrderStatus(order.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", order.id()));
      assertEquals(1, momoIpnEventCountForOrder(order.id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void momoTestReconciliationSettlesVerifiedOrdersAndBoundsRetriesAcrossWorkers() throws Exception {
    UUID userId = createSessionTestUser("MoMo reconciliation owner");
    try {
      CreditTopUpOrderService.TopUpOrder paidOrder = createPendingMomoTestTopUp(userId);
      AtomicInteger successfulQueries = new AtomicInteger();
      MomoTestOrderReconciliationService secondWorker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) -> {
                throw new AssertionError("lease should prevent a concurrent second query");
              },
              5);
      MomoTestOrderReconciliationService successWorker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) -> {
                successfulQueries.incrementAndGet();
                assertEquals("MOMOTEST123", request.partnerCode());
                assertEquals(paidOrder.id().toString(), request.orderId());
                assertEquals(1_000, amountVnd);
                assertEquals(0, secondWorker.reconcileDueBatch());
                return new MomoTestGatewayClient.QueryResult(
                    com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome.SUCCEEDED,
                    8_777_000_001L,
                    MomoTestGatewayClient.FailureCode.NONE);
              },
              5);

      assertEquals(1, successWorker.reconcileDueBatch());
      assertEquals(0, successWorker.reconcileDueBatch());
      assertEquals(1, successfulQueries.get());
      assertEquals(CreditTopUpOrderService.OrderStatus.SUCCEEDED, topUpOrderStatus(paidOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", paidOrder.id()));
      assertEquals(1, momoReconciliationEventCount(paidOrder.id()));

      CreditTopUpOrderService.TopUpOrder pendingOrder = createPendingMomoTestTopUp(userId);
      AtomicInteger pendingQueries = new AtomicInteger();
      MomoTestOrderReconciliationService pendingWorker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) -> {
                pendingQueries.incrementAndGet();
                return new MomoTestGatewayClient.QueryResult(
                    com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome.PENDING,
                    null,
                    MomoTestGatewayClient.FailureCode.NONE);
              },
              5);
      assertEquals(1, pendingWorker.reconcileDueBatch());
      assertEquals(0, pendingWorker.reconcileDueBatch());
      assertEquals(1, pendingQueries.get());
      assertEquals(
          CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(pendingOrder.id()));
      assertEquals("PENDING", latestMomoReconciliationOutcome(pendingOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      setTopUpReconciliationDue(pendingOrder.id(), 7);

      MomoTestOrderReconciliationService exhaustedWorker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) ->
                  new MomoTestGatewayClient.QueryResult(
                      com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome.UNKNOWN,
                      null,
                      MomoTestGatewayClient.FailureCode.TRANSPORT),
              5);
      assertEquals(1, exhaustedWorker.reconcileDueBatch());
      assertEquals(
          CreditTopUpOrderService.OrderStatus.UNKNOWN, topUpOrderStatus(pendingOrder.id()));
      assertEquals("ATTEMPTS_EXHAUSTED", topUpReconciliationReviewCode(pendingOrder.id()));
      assertEquals(2, momoReconciliationEventCount(pendingOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", pendingOrder.id()));
      assertEquals(0, exhaustedWorker.reconcileDueBatch());
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void momoTestReconciliationQuarantinesReusedProviderTransactionsAndKeepsAuditAppendOnly()
      throws Exception {
    UUID userId = createSessionTestUser("MoMo reconciliation collision owner");
    UUID reconciledOrderId = null;
    try {
      CreditTopUpOrderService.TopUpOrder firstOrder = createPendingMomoTestTopUp(userId);
      CreditTopUpOrderService.TopUpOrder reconciledOrder = createPendingMomoTestTopUp(userId);
      reconciledOrderId = reconciledOrder.id();
      SignedNotification firstPaid =
          MomoTestIpnTestSupport.sign(
              firstOrder.id().toString(),
              firstOrder.requestId().toString(),
              1_000,
              0,
              8_777_000_002L);
      assertEquals(
          MomoTestIpnService.Result.SUCCEEDED,
          momoTestIpnService.process(firstPaid.notification(), firstPaid.signature()));

      MomoTestOrderReconciliationService worker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) ->
                  new MomoTestGatewayClient.QueryResult(
                      com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome.SUCCEEDED,
                      8_777_000_002L,
                      MomoTestGatewayClient.FailureCode.NONE),
              5);
      assertEquals(1, worker.reconcileDueBatch());
      assertEquals(
          CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(reconciledOrder.id()));
      assertEquals("TRANSACTION_ID_REUSED", topUpReconciliationReviewCode(reconciledOrder.id()));
      assertEquals("QUARANTINED", latestMomoReconciliationOutcome(reconciledOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", firstOrder.id()));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", reconciledOrder.id()));
      assertEquals(1, momoReconciliationEventCount(reconciledOrder.id()));
      assertThrows(
          SQLException.class,
          () -> {
            try (Connection connection = dataSource.getConnection();
                PreparedStatement update =
                    connection.prepareStatement(
                        "UPDATE momo_test_reconciliation_events SET outcome = 'UNKNOWN' WHERE credit_top_up_order_id = ?")) {
              update.setObject(1, reconciledOrder.id());
              update.executeUpdate();
            }
          });
    } finally {
      deleteSessionTestUser(userId);
    }
    assertEquals(0, momoReconciliationEventCount(reconciledOrderId));
  }

  @Test
  void momoTestReconciliationRejectsLateSuccessForAnAlreadyFailedOrder() throws Exception {
    UUID userId = createSessionTestUser("MoMo reconciliation late success owner");
    try {
      CreditTopUpOrderService.TopUpOrder order = createPendingMomoTestTopUp(userId);
      MomoTestOrderReconciliationService worker =
          new MomoTestOrderReconciliationService(
              dataSource,
              creditLedgerService,
              "MOMOTEST123",
              (request, amountVnd) -> {
                SignedNotification failedNotification =
                    MomoTestIpnTestSupport.sign(
                        order.id().toString(), order.requestId().toString(), 1_000, 1_001, 0);
                assertEquals(
                    MomoTestIpnService.Result.FAILED,
                    momoTestIpnService.process(
                        failedNotification.notification(), failedNotification.signature()));
                return new MomoTestGatewayClient.QueryResult(
                    com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome.SUCCEEDED,
                    8_777_000_003L,
                    MomoTestGatewayClient.FailureCode.NONE);
              },
              5);

      assertEquals(1, worker.reconcileDueBatch());
      assertEquals(CreditTopUpOrderService.OrderStatus.FAILED, topUpOrderStatus(order.id()));
      assertEquals("TERMINAL_STATUS_CONFLICT", topUpReconciliationReviewCode(order.id()));
      assertEquals("QUARANTINED", latestMomoReconciliationOutcome(order.id()));
      assertEquals(0, creditProjectedBalance(userId, "PAID"));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", order.id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void validSignedMomoIpnWithWrongAmountOrRequestIdIsAuditedWithoutGrantingCredits()
      throws Exception {
    UUID userId = createSessionTestUser("MoMo IPN Mismatch Owner");
    try {
      CreditTopUpOrderService.TopUpOrder amountOrder = createPendingMomoTestTopUp(userId);
      SignedNotification wrongAmount =
          MomoTestIpnTestSupport.sign(
              amountOrder.id().toString(),
              amountOrder.requestId().toString(),
              1_001,
              0,
              8_765_432_102L);
      assertEquals(
          MomoTestIpnService.Result.ORDER_MISMATCH,
          momoTestIpnService.process(wrongAmount.notification(), wrongAmount.signature()));

      CreditTopUpOrderService.TopUpOrder requestOrder = createPendingMomoTestTopUp(userId);
      SignedNotification wrongRequest =
          MomoTestIpnTestSupport.sign(
              requestOrder.id().toString(), UUID.randomUUID().toString(), 1_000, 0, 8_765_432_103L);
      assertEquals(
          MomoTestIpnService.Result.ORDER_MISMATCH,
          momoTestIpnService.process(wrongRequest.notification(), wrongRequest.signature()));

      assertEquals(CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(amountOrder.id()));
      assertEquals(
          CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(requestOrder.id()));
      assertEquals(0, creditProjectedBalance(userId, "PAID"));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", amountOrder.id()));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", requestOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(amountOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(requestOrder.id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void momo9000ResultUsesTheSavedCaptureModeAndInvalidSignatureNeverGrantsCredits()
      throws Exception {
    UUID userId = createSessionTestUser("MoMo IPN Authorization Owner");
    try {
      CreditTopUpOrderService.TopUpOrder oneStepOrder = createPendingMomoTestTopUp(userId);
      SignedNotification oneStepAuthorized =
          MomoTestIpnTestSupport.sign(
              oneStepOrder.id().toString(),
              oneStepOrder.requestId().toString(),
              1_000,
              9_000,
              8_765_432_104L);
      assertEquals(
          MomoTestIpnService.Result.SUCCEEDED,
          momoTestIpnService.process(
              oneStepAuthorized.notification(), oneStepAuthorized.signature()));
      assertEquals(
          CreditTopUpOrderService.OrderStatus.SUCCEEDED, topUpOrderStatus(oneStepOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", oneStepOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(oneStepOrder.id()));

      CreditTopUpOrderService.TopUpOrder twoStepOrder = createPendingMomoTestTopUp(userId);
      setTopUpAutoCapture(twoStepOrder.id(), false);
      SignedNotification twoStepAuthorized =
          MomoTestIpnTestSupport.sign(
              twoStepOrder.id().toString(),
              twoStepOrder.requestId().toString(),
              1_000,
              9_000,
              8_765_432_105L);
      assertEquals(
          MomoTestIpnService.Result.PENDING,
          momoTestIpnService.process(
              twoStepAuthorized.notification(), twoStepAuthorized.signature()));
      assertEquals(
          CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(twoStepOrder.id()));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", twoStepOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(twoStepOrder.id()));

      SignedNotification invalid =
          MomoTestIpnTestSupport.sign(
              twoStepOrder.id().toString(),
              twoStepOrder.requestId().toString(),
              1_000,
              0,
              8_765_432_106L);
      assertEquals(
          MomoTestIpnService.Result.INVALID_SIGNATURE,
          momoTestIpnService.process(invalid.notification(), "0".repeat(64)));
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", twoStepOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(twoStepOrder.id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void failedResultAndTransactionIdReusedByAnotherOrderNeverDoubleGrant() throws Exception {
    UUID firstUserId = createSessionTestUser("MoMo IPN Transaction Owner");
    UUID otherUserId = createSessionTestUser("MoMo IPN Transaction Collision");
    try {
      CreditTopUpOrderService.TopUpOrder firstOrder = createPendingMomoTestTopUp(firstUserId);
      CreditTopUpOrderService.TopUpOrder otherOrder = createPendingMomoTestTopUp(otherUserId);
      long reusedTransactionId = 8_765_432_107L;
      SignedNotification firstSuccess =
          MomoTestIpnTestSupport.sign(
              firstOrder.id().toString(),
              firstOrder.requestId().toString(),
              1_000,
              0,
              reusedTransactionId);
      assertEquals(
          MomoTestIpnService.Result.SUCCEEDED,
          momoTestIpnService.process(firstSuccess.notification(), firstSuccess.signature()));

      SignedNotification collision =
          MomoTestIpnTestSupport.sign(
              otherOrder.id().toString(),
              otherOrder.requestId().toString(),
              1_000,
              0,
              reusedTransactionId);
      assertEquals(
          MomoTestIpnService.Result.TRANSACTION_CONFLICT,
          momoTestIpnService.process(collision.notification(), collision.signature()));

      CreditTopUpOrderService.TopUpOrder failedOrder = createPendingMomoTestTopUp(firstUserId);
      SignedNotification failed =
          MomoTestIpnTestSupport.sign(
              failedOrder.id().toString(), failedOrder.requestId().toString(), 1_000, 1001, 0);
      assertEquals(
          MomoTestIpnService.Result.FAILED,
          momoTestIpnService.process(failed.notification(), failed.signature()));

      assertEquals(
          CreditTopUpOrderService.OrderStatus.SUCCEEDED, topUpOrderStatus(firstOrder.id()));
      assertEquals(CreditTopUpOrderService.OrderStatus.PENDING, topUpOrderStatus(otherOrder.id()));
      assertEquals(CreditTopUpOrderService.OrderStatus.FAILED, topUpOrderStatus(failedOrder.id()));
      assertEquals(100, creditProjectedBalance(firstUserId, "PAID"));
      assertEquals(0, creditProjectedBalance(otherUserId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", firstOrder.id()));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", otherOrder.id()));
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", failedOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(firstOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(otherOrder.id()));
      assertEquals(1, momoIpnEventCountForOrder(failedOrder.id()));
    } finally {
      deleteSessionTestUser(firstUserId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void concurrentDuplicateMomoSuccessNotificationsAppendOneCreditGrant() throws Exception {
    UUID userId = createSessionTestUser("Concurrent MoMo IPN Owner");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CreditTopUpOrderService.TopUpOrder order = createPendingMomoTestTopUp(userId);
      SignedNotification signed =
          MomoTestIpnTestSupport.sign(
              order.id().toString(), order.requestId().toString(), 1_000, 0, 8_765_432_106L);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<MomoTestIpnService.Result>> attempts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        attempts.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("duplicate MoMo IPN calls did not start");
                  }
                  return momoTestIpnService.process(signed.notification(), signed.signature());
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<MomoTestIpnService.Result> outcomes =
          List.of(
              attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS));

      assertEquals(
          1,
          outcomes.stream().filter(value -> value == MomoTestIpnService.Result.SUCCEEDED).count());
      assertEquals(
          1,
          outcomes.stream().filter(value -> value == MomoTestIpnService.Result.DUPLICATE).count());
      assertEquals(100, creditProjectedBalance(userId, "PAID"));
      assertEquals(1, creditSourceEntryCount("PAYMENT_ORDER", order.id()));
      assertEquals(1, momoIpnEventCountForOrder(order.id()));
    } finally {
      executor.shutdownNow();
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void ambiguousCreditTopUpCreateIsNotRetriedAndForeignActorCannotReadOrderState()
      throws Exception {
    UUID userId = createSessionTestUser("Unknown Top-up Checkout Owner");
    UUID otherUserId = createSessionTestUser("Foreign Top-up Checkout Owner");
    String idempotencyKey = "credit-top-up-checkout-unknown-01";
    AtomicInteger providerCalls = new AtomicInteger();
    CreditTopUpCheckoutService checkout =
        new CreditTopUpCheckoutService(
            creditTopUpOrderService,
            (amountVnd, orderId, requestId) -> {
              providerCalls.incrementAndGet();
              return new MomoTestGatewayClient.CreateResult(
                  MomoTestGatewayClient.CreateState.UNKNOWN,
                  null,
                  MomoTestGatewayClient.FailureCode.TRANSPORT);
            });
    try {
      CreditTopUpCheckoutService.CheckoutResult first =
          checkout.createOrReplay(
              userId, idempotencyKey, CreditPackCatalog.momoTestFixture(), "momo-test-credits-100");
      assertEquals(
          CreditTopUpOrderService.CheckoutOutcome.PROVIDER_RESULT_UNKNOWN, first.checkoutOutcome());
      assertEquals(CreditTopUpOrderService.OrderStatus.UNKNOWN, first.order().status());
      assertNull(first.checkoutUrl());

      CreditTopUpOrderService.CheckoutClaim foreign =
          creditTopUpOrderService.claimProviderCreate(otherUserId, first.order().id());
      assertEquals(CreditTopUpOrderService.CheckoutOutcome.ORDER_NOT_FOUND, foreign.outcome());

      CreditTopUpCheckoutService.CheckoutResult replay =
          checkout.createOrReplay(
              userId, idempotencyKey, CreditPackCatalog.momoTestFixture(), "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.Outcome.REPLAYED, replay.creationOutcome());
      assertEquals(
          CreditTopUpOrderService.CheckoutOutcome.PROVIDER_RESULT_UNKNOWN,
          replay.checkoutOutcome());
      assertEquals(1, providerCalls.get());
      assertEquals(0, creditSourceEntryCount("PAYMENT_ORDER", first.order().id()));
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void concurrentSameKeyCheckoutClaimsOnlyOneProviderCreateAttempt() throws Exception {
    UUID userId = createSessionTestUser("Concurrent Checkout Owner");
    String idempotencyKey = "credit-top-up-checkout-race-01";
    AtomicInteger providerCalls = new AtomicInteger();
    CountDownLatch providerStarted = new CountDownLatch(1);
    CountDownLatch finishProvider = new CountDownLatch(1);
    CreditTopUpCheckoutService checkout =
        new CreditTopUpCheckoutService(
            creditTopUpOrderService,
            (amountVnd, orderId, requestId) -> {
              providerCalls.incrementAndGet();
              providerStarted.countDown();
              try {
                if (!finishProvider.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("synthetic provider response was not released");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "synthetic provider call was interrupted", exception);
              }
              return new MomoTestGatewayClient.CreateResult(
                  MomoTestGatewayClient.CreateState.READY,
                  "https://test-payment.momo.vn/v2/gateway/pay?t=race&s=synthetic",
                  MomoTestGatewayClient.FailureCode.NONE);
            });
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CreditTopUpCheckoutService.CheckoutResult> first =
          executor.submit(
              () ->
                  checkout.createOrReplay(
                      userId,
                      idempotencyKey,
                      CreditPackCatalog.momoTestFixture(),
                      "momo-test-credits-100"));
      assertTrue(providerStarted.await(10, TimeUnit.SECONDS));

      CreditTopUpCheckoutService.CheckoutResult concurrentReplay =
          checkout.createOrReplay(
              userId, idempotencyKey, CreditPackCatalog.momoTestFixture(), "momo-test-credits-100");
      assertEquals(
          CreditTopUpOrderService.CheckoutOutcome.PROVIDER_CREATE_IN_PROGRESS,
          concurrentReplay.checkoutOutcome());
      assertNull(concurrentReplay.checkoutUrl());

      finishProvider.countDown();
      CreditTopUpCheckoutService.CheckoutResult completed = first.get(15, TimeUnit.SECONDS);
      assertEquals(CreditTopUpOrderService.CheckoutOutcome.READY, completed.checkoutOutcome());
      assertEquals(concurrentReplay.order().id(), completed.order().id());
      assertEquals(1, providerCalls.get());
    } finally {
      finishProvider.countDown();
      executor.shutdownNow();
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void lateProviderCreateResponseCannotOverwriteAnAlreadyTerminalOrder() throws Exception {
    UUID userId = createSessionTestUser("Late Checkout Response Owner");
    CreditTopUpCheckoutService checkout =
        new CreditTopUpCheckoutService(
            creditTopUpOrderService,
            (amountVnd, orderId, requestId) -> {
              markTopUpOrderFailed(userId, orderId);
              return new MomoTestGatewayClient.CreateResult(
                  MomoTestGatewayClient.CreateState.READY,
                  "https://test-payment.momo.vn/v2/gateway/pay?t=late&s=synthetic",
                  MomoTestGatewayClient.FailureCode.NONE);
            });
    try {
      CreditTopUpCheckoutService.CheckoutResult result =
          checkout.createOrReplay(
              userId,
              "credit-top-up-checkout-late-01",
              CreditPackCatalog.momoTestFixture(),
              "momo-test-credits-100");
      assertEquals(CreditTopUpOrderService.CheckoutOutcome.TERMINAL, result.checkoutOutcome());
      assertEquals(CreditTopUpOrderService.OrderStatus.FAILED, result.order().status());
      assertNull(result.checkoutUrl());
      assertNull(storedTopUpCheckoutUrl(userId, result.order().id()));
    } finally {
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void creditTopUpOrderSerializesConcurrentRequestsWithTheSameActorKey() throws Exception {
    UUID userId = createSessionTestUser("Concurrent Top-up Order Owner");
    String idempotencyKey = "credit-top-up-order-race-0001";
    CreditPackCatalog catalog = CreditPackCatalog.momoTestFixture();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<CreditTopUpOrderService.CreateResult>> requests = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        requests.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "concurrent top-up order requests did not start");
                  }
                  return creditTopUpOrderService.createOrReplay(
                      userId, idempotencyKey, catalog, "momo-test-credits-100");
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<CreditTopUpOrderService.CreateResult> results =
          List.of(
              requests.get(0).get(15, TimeUnit.SECONDS), requests.get(1).get(15, TimeUnit.SECONDS));
      assertEquals(
          1,
          results.stream()
              .filter(result -> result.outcome() == CreditTopUpOrderService.Outcome.CREATED)
              .count());
      assertEquals(
          1,
          results.stream()
              .filter(result -> result.outcome() == CreditTopUpOrderService.Outcome.REPLAYED)
              .count());
      assertEquals(results.getFirst().order().id(), results.getLast().order().id());
      assertEquals(results.getFirst().order().requestId(), results.getLast().order().requestId());
      assertEquals(1, creditTopUpOrderCount(userId));
    } finally {
      executor.shutdownNow();
      deleteSessionTestUser(userId);
    }
  }

  @Test
  void paymentOrderHistorySeparatesAttemptsFromGrantedCreditsAndBindsFilterAndActorCursors()
      throws Exception {
    UUID userId = createSessionTestUser("Payment History Owner");
    UUID otherUserId = createSessionTestUser("Payment History Other Owner");
    try {
      addCurrentPolicyAcceptances(userId);
      addCurrentPolicyAcceptances(otherUserId);
      CreditTopUpOrderService.TopUpOrder pendingFirst = createPendingMomoTestTopUp(userId);
      CreditTopUpOrderService.TopUpOrder pendingSecond = createPendingMomoTestTopUp(userId);
      CreditTopUpOrderService.TopUpOrder succeeded = createPendingMomoTestTopUp(userId);
      SignedNotification successNotification =
          MomoTestIpnTestSupport.sign(
              succeeded.id().toString(),
              succeeded.requestId().toString(),
              1_000,
              0,
              8_765_433_201L);
      assertEquals(
          MomoTestIpnService.Result.SUCCEEDED,
          momoTestIpnService.process(
              successNotification.notification(), successNotification.signature()));
      CreditTopUpOrderService.TopUpOrder created =
          creditTopUpOrderService
              .createOrReplay(
                  userId,
                  "credit-history-created-order-01",
                  creditPackCatalog,
                  "momo-test-credits-100")
              .order();

      SessionService.IssuedSession ownerSession = sessionService.create(userId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
      jakarta.servlet.http.Cookie ownerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", ownerSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());

      MvcResult ledgerHistory =
          mockMvc.perform(get("/api/v1/me/credits/transactions").cookie(ownerCookie)).andReturn();
      assertEquals(200, ledgerHistory.getResponse().getStatus());
      assertTrue(ledgerHistory.getResponse().getHeader("Cache-Control").contains("no-store"));

      Set<UUID> observed = new HashSet<>();
      String cursor = null;
      for (int pageIndex = 0; pageIndex < 4; pageIndex++) {
        var request = get("/api/v1/me/payment-orders").cookie(ownerCookie).param("limit", "1");
        if (cursor != null) request.param("cursor", cursor);
        MvcResult page = mockMvc.perform(request).andReturn();
        assertEquals(200, page.getResponse().getStatus());
        assertTrue(page.getResponse().getHeader("Cache-Control").contains("no-store"));
        JsonNode body = objectMapper.readTree(page.getResponse().getContentAsString());
        JsonNode order = body.path("items").get(0);
        assertNotNull(order);
        observed.add(UUID.fromString(order.path("id").asText()));
        assertFalse(page.getResponse().getContentAsString().contains("checkoutUrl"));
        assertFalse(page.getResponse().getContentAsString().contains("requestId"));
        assertFalse(page.getResponse().getContentAsString().contains(userId.toString()));
        cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText();
        if (pageIndex < 3) assertNotNull(cursor);
        else assertNull(cursor);
      }
      assertEquals(
          Set.of(pendingFirst.id(), pendingSecond.id(), succeeded.id(), created.id()), observed);

      MvcResult pendingPage =
          mockMvc
              .perform(
                  get("/api/v1/me/payment-orders")
                      .cookie(ownerCookie)
                      .param("state", "PENDING")
                      .param("limit", "1"))
              .andReturn();
      JsonNode pendingBody = objectMapper.readTree(pendingPage.getResponse().getContentAsString());
      assertEquals(200, pendingPage.getResponse().getStatus());
      assertEquals("PENDING", pendingBody.path("items").get(0).path("status").asText());
      assertEquals(0, pendingBody.path("items").get(0).path("paidCreditsGranted").asInt());
      String pendingCursor = pendingBody.path("nextCursor").asText();
      MvcResult changedFilter =
          mockMvc
              .perform(
                  get("/api/v1/me/payment-orders")
                      .cookie(ownerCookie)
                      .param("state", "SUCCEEDED")
                      .param("cursor", pendingCursor))
              .andReturn();
      assertEquals(400, changedFilter.getResponse().getStatus());
      assertTrue(changedFilter.getResponse().getContentAsString().contains("INVALID_CURSOR"));

      MvcResult succeededPage =
          mockMvc
              .perform(
                  get("/api/v1/me/payment-orders").cookie(ownerCookie).param("state", "SUCCEEDED"))
              .andReturn();
      JsonNode succeededBody =
          objectMapper.readTree(succeededPage.getResponse().getContentAsString());
      assertEquals(200, succeededPage.getResponse().getStatus());
      assertEquals(
          succeeded.id().toString(), succeededBody.path("items").get(0).path("id").asText());
      assertEquals(100, succeededBody.path("items").get(0).path("paidCreditsGranted").asInt());

      MvcResult foreignPage =
          mockMvc.perform(get("/api/v1/me/payment-orders").cookie(otherCookie)).andReturn();
      assertEquals(200, foreignPage.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(foreignPage.getResponse().getContentAsString())
              .path("items")
              .size());
      MvcResult foreignCursor =
          mockMvc
              .perform(
                  get("/api/v1/me/payment-orders")
                      .cookie(otherCookie)
                      .param("cursor", pendingCursor))
              .andReturn();
      assertEquals(400, foreignCursor.getResponse().getStatus());
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
    }
  }

  @Test
  void purchaseHistoryReturnsOnlyActorEntitlementsAndCurrentReadAccess() throws Exception {
    UUID authorId = createSessionTestUser("Purchase History Author");
    UUID buyerId = createSessionTestUser("Purchase History Buyer");
    UUID otherBuyerId = createSessionTestUser("Purchase History Other Buyer");
    SyntheticPublicationScopeFixture publicationFixture = null;
    try {
      addCurrentPolicyAcceptances(buyerId);
      addCurrentPolicyAcceptances(otherBuyerId);
      LibraryRepository.LibraryRecord library =
          libraryService.createLibrary(authorId, "Purchase History Shelf");
      LibraryRepository.WorkRecord work =
          libraryService.createPrivateImportedWork(
              authorId,
              library.id(),
              "Purchase History Story",
              "Synthetic Author",
              "vi",
              sha256("purchase-history-fixture"));
      LibraryRepository.VolumeRecord volume =
          libraryService.createVolume(authorId, work.id(), 1, "Volume 1");
      LibraryRepository.ChapterRecord firstChapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 1, "Chapter 1", "vi");
      LibraryRepository.ChapterRecord secondChapter =
          libraryService.createChapter(authorId, work.id(), volume.id(), 2, "Chapter 2", "vi");
      libraryService.appendChapterRevision(
          authorId,
          firstChapter.id(),
          "plain_text",
          "Private test body one.",
          List.of(new LibraryService.NewSegment(1, "paragraph", "Private test body one.", "ph-1")));
      libraryService.appendChapterRevision(
          authorId,
          secondChapter.id(),
          "plain_text",
          "Private test body two.",
          List.of(new LibraryService.NewSegment(1, "paragraph", "Private test body two.", "ph-2")));
      publicationFixture =
          approveSyntheticPublicationScopes(
              authorId, work.id(), "PUBLIC_METADATA", "CHAPTER_TEXT", "COMMERCIAL_SALE");
      inCreditLedgerTransaction(
          connection ->
              creditLedgerService.grantPaidTopUp(connection, buyerId, UUID.randomUUID(), 20));
      assertEquals(
          ChapterUnlockService.Outcome.CREATED,
          chapterUnlockService
              .unlock(buyerId, firstChapter.id(), 1, "purchase-history-unlock-001")
              .outcome());
      assertEquals(
          ChapterUnlockService.Outcome.CREATED,
          chapterUnlockService
              .unlock(buyerId, secondChapter.id(), 1, "purchase-history-unlock-002")
              .outcome());

      SessionService.IssuedSession buyerSession = sessionService.create(buyerId).orElseThrow();
      SessionService.IssuedSession otherSession = sessionService.create(otherBuyerId).orElseThrow();
      jakarta.servlet.http.Cookie buyerCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", buyerSession.cookieValue());
      jakarta.servlet.http.Cookie otherCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", otherSession.cookieValue());

      MvcResult firstPage =
          mockMvc
              .perform(get("/api/v1/me/purchases").cookie(buyerCookie).param("limit", "1"))
              .andReturn();
      assertEquals(200, firstPage.getResponse().getStatus());
      assertTrue(firstPage.getResponse().getHeader("Cache-Control").contains("no-store"));
      JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
      assertEquals(1, firstBody.path("items").size());
      assertEquals("READABLE", firstBody.path("items").get(0).path("currentReadAccess").asText());
      assertEquals(10, firstBody.path("items").get(0).path("creditsSpent").asInt());
      assertFalse(firstPage.getResponse().getContentAsString().contains("Private test body"));
      String cursor = firstBody.path("nextCursor").asText();
      assertFalse(cursor.isBlank());

      MvcResult secondPage =
          mockMvc
              .perform(
                  get("/api/v1/me/purchases")
                      .cookie(buyerCookie)
                      .param("limit", "1")
                      .param("cursor", cursor))
              .andReturn();
      JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
      assertEquals(200, secondPage.getResponse().getStatus());
      assertEquals(1, secondBody.path("items").size());
      assertNotEquals(
          firstBody.path("items").get(0).path("entitlementId").asText(),
          secondBody.path("items").get(0).path("entitlementId").asText());
      assertTrue(secondBody.path("nextCursor").isNull());

      MvcResult foreignCursor =
          mockMvc
              .perform(get("/api/v1/me/purchases").cookie(otherCookie).param("cursor", cursor))
              .andReturn();
      assertEquals(400, foreignCursor.getResponse().getStatus());
      MvcResult emptyOtherHistory =
          mockMvc.perform(get("/api/v1/me/purchases").cookie(otherCookie)).andReturn();
      assertEquals(200, emptyOtherHistory.getResponse().getStatus());
      assertEquals(
          0,
          objectMapper
              .readTree(emptyOtherHistory.getResponse().getContentAsString())
              .path("items")
              .size());

      revokeSyntheticPublicationScope(work.id(), publicationFixture, "CHAPTER_TEXT");
      MvcResult revokedReadAccess =
          mockMvc.perform(get("/api/v1/me/purchases").cookie(buyerCookie)).andReturn();
      assertEquals(200, revokedReadAccess.getResponse().getStatus());
      assertTrue(revokedReadAccess.getResponse().getContentAsString().contains("UNAVAILABLE"));

      setWorkVisibility(work.id(), "private");
      MvcResult unavailableContent =
          mockMvc.perform(get("/api/v1/me/purchases").cookie(buyerCookie)).andReturn();
      JsonNode unavailableBody =
          objectMapper.readTree(unavailableContent.getResponse().getContentAsString());
      assertEquals(200, unavailableContent.getResponse().getStatus());
      assertTrue(unavailableBody.path("items").toString().contains("UNAVAILABLE"));
      assertEquals(2, contentEntitlementCount(buyerId));
    } finally {
      deleteSessionTestUser(buyerId);
      deleteSessionTestUser(otherBuyerId);
      deleteLibraryTestData(authorId);
      cleanupSyntheticPublicationScopeFixture(publicationFixture);
      deleteSessionTestUser(authorId);
    }
  }

  @Test
  void creditHistoryUsesActorBoundKeysetPagesAndNeverIncludesForeignEntries() throws Exception {
    UUID userId = createSessionTestUser("Credit History Owner");
    UUID otherUserId = createSessionTestUser("Credit History Other Owner");
    UUID emptyUserId = createSessionTestUser("Credit History Empty Owner");
    try {
      assertEquals(
          CreditLedgerService.Outcome.APPLIED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(
                          connection, userId, UUID.randomUUID(), 100))
              .outcome());
      UUID unlockId = UUID.randomUUID();
      assertEquals(
          CreditLedgerService.Outcome.APPLIED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.debitUnlock(
                          connection,
                          userId,
                          unlockId,
                          10,
                          List.of(
                              new CreditLedgerService.Allocation(
                                  CreditLedgerService.Bucket.PAID, 10, null))))
              .outcome());
      insertCreditLedgerEntry(
          userId,
          "BONUS_GRANT",
          "BONUS",
          5,
          Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS),
          "OPERATOR",
          UUID.randomUUID(),
          null);
      assertEquals(
          CreditLedgerService.Outcome.APPLIED,
          inCreditLedgerTransaction(
                  connection ->
                      creditLedgerService.grantPaidTopUp(
                          connection, otherUserId, UUID.randomUUID(), 50))
              .outcome());

      CreditHistoryService.HistoryPage firstPage =
          creditHistoryService.list(userId, null, 2).orElseThrow();
      assertEquals(2, firstPage.items().size());
      assertNotNull(firstPage.nextCursor());
      CreditHistoryService.HistoryPage secondPage =
          creditHistoryService.list(userId, firstPage.nextCursor(), 2).orElseThrow();
      assertEquals(1, secondPage.items().size());
      assertNull(secondPage.nextCursor());
      List<CreditHistoryRepository.CreditEntry> allEntries = new ArrayList<>(firstPage.items());
      allEntries.addAll(secondPage.items());
      assertEquals(3, allEntries.size());
      assertEquals(
          3, allEntries.stream().map(CreditHistoryRepository.CreditEntry::id).distinct().count());
      assertTrue(
          allEntries.stream()
              .anyMatch(
                  entry ->
                      entry.entryType().equals("UNLOCK_DEBIT")
                          && entry.amountDelta().longValue() == -10));
      assertTrue(
          allEntries.stream()
              .anyMatch(
                  entry ->
                      entry.entryType().equals("BONUS_GRANT") && entry.bucket().equals("BONUS")));
      assertTrue(allEntries.get(0).occurredAt().compareTo(allEntries.get(1).occurredAt()) >= 0);

      assertThrows(
          com.novelweb.payment.credit.CreditHistoryCursorCodec.InvalidCursorException.class,
          () -> creditHistoryService.list(otherUserId, firstPage.nextCursor(), 2));
      CreditHistoryService.HistoryPage otherActorPage =
          creditHistoryService.list(otherUserId, null, 2).orElseThrow();
      assertEquals(1, otherActorPage.items().size());
      assertEquals(50, otherActorPage.items().getFirst().amountDelta().longValue());
      assertTrue(creditHistoryService.list(emptyUserId, null, 2).orElseThrow().items().isEmpty());
    } finally {
      deleteSessionTestUser(userId);
      deleteSessionTestUser(otherUserId);
      deleteSessionTestUser(emptyUserId);
    }
  }

  private long creditTopUpOrderCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM credit_top_up_orders WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private CreditTopUpOrderService.TopUpOrder createPendingMomoTestTopUp(UUID userId)
      throws SQLException {
    CreditTopUpCheckoutService checkout =
        new CreditTopUpCheckoutService(
            creditTopUpOrderService,
            (amountVnd, orderId, requestId) ->
                new MomoTestGatewayClient.CreateResult(
                    MomoTestGatewayClient.CreateState.READY,
                    "https://test-payment.momo.vn/v2/gateway/pay?t=synthetic-ipn"
                        + "&order="
                        + orderId,
                    MomoTestGatewayClient.FailureCode.NONE));
    CreditTopUpCheckoutService.CheckoutResult result =
        checkout.createOrReplay(
            userId,
            "momo-ipn-" + UUID.randomUUID(),
            CreditPackCatalog.momoTestFixture(),
            "momo-test-credits-100");
    assertEquals(CreditTopUpOrderService.CheckoutOutcome.READY, result.checkoutOutcome());
    assertEquals(CreditTopUpOrderService.OrderStatus.PENDING, result.order().status());
    assertEquals(1_000, result.order().quote().amountVnd());
    assertEquals(100, result.order().quote().paidCredits());
    assertTrue(topUpAutoCapture(result.order().id()));
    return result.order();
  }

  private boolean topUpAutoCapture(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT auto_capture FROM credit_top_up_orders WHERE id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean("auto_capture");
      }
    }
  }

  private CreditTopUpOrderService.OrderStatus topUpOrderStatus(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT status FROM credit_top_up_orders WHERE id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return CreditTopUpOrderService.OrderStatus.valueOf(row.getString("status"));
      }
    }
  }

  private long momoIpnEventCountForOrder(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM momo_test_ipn_events WHERE credit_top_up_order_id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private long momoReconciliationEventCount(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM momo_test_reconciliation_events WHERE credit_top_up_order_id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private String latestMomoReconciliationOutcome(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT outcome FROM momo_test_reconciliation_events
                    WHERE credit_top_up_order_id = ?
                    ORDER BY queried_at DESC, query_request_id DESC
                    LIMIT 1
                    """)) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getString("outcome") : null;
      }
    }
  }

  private String topUpReconciliationReviewCode(UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT reconciliation_review_code FROM credit_top_up_orders WHERE id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getString("reconciliation_review_code") : null;
      }
    }
  }

  private void setTopUpReconciliationDue(UUID orderId, int attempts) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE credit_top_up_orders
                    SET reconciliation_attempt_count = ?,
                        next_reconciliation_at = clock_timestamp() - INTERVAL '1 second',
                        reconciliation_lease_until = NULL,
                        reconciliation_review_required_at = NULL,
                        reconciliation_review_code = NULL
                    WHERE id = ?
                    """)) {
      update.setInt(1, attempts);
      update.setObject(2, orderId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private String storedTopUpCheckoutUrl(UUID userId, UUID orderId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT checkout_url FROM credit_top_up_orders WHERE user_id = ? AND id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getString("checkout_url") : null;
      }
    }
  }

  private void setTopUpAutoCapture(UUID orderId, boolean autoCapture) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE credit_top_up_orders SET auto_capture = ? WHERE id = ?")) {
      update.setBoolean(1, autoCapture);
      update.setObject(2, orderId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void markTopUpOrderFailed(UUID userId, UUID orderId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE credit_top_up_orders
                    SET status = 'FAILED', checkout_url = NULL, updated_at = clock_timestamp()
                    WHERE user_id = ? AND id = ? AND status = 'INITIATING'
                    """)) {
      update.setObject(1, userId);
      update.setObject(2, orderId);
      assertEquals(1, update.executeUpdate());
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to mark synthetic terminal checkout state", exception);
    }
  }

  private <T> T inCreditLedgerTransaction(CreditLedgerWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        try {
          connection.rollback();
        } catch (SQLException rollbackException) {
          exception.addSuppressed(rollbackException);
        }
        throw exception;
      }
    }
  }

  @FunctionalInterface
  private interface CreditLedgerWork<T> {
    T apply(Connection connection) throws SQLException;
  }

  private long creditSourceEntryCount(String sourceType, UUID sourceId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM credit_ledger_entries WHERE source_type = ? AND source_id = ?")) {
      select.setString(1, sourceType);
      select.setObject(2, sourceId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private static void insertCreditEntry(
      Connection connection,
      UUID userId,
      String entryType,
      String bucket,
      long amountDelta,
      Instant expiresAt,
      String sourceType,
      UUID sourceId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO credit_ledger_entries
                (user_id, entry_type, bucket, amount_delta, expires_at, source_type, source_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      insert.setObject(1, userId);
      insert.setString(2, entryType);
      insert.setString(3, bucket);
      insert.setLong(4, amountDelta);
      insert.setTimestamp(
          5, expiresAt == null ? null : Timestamp.from(expiresAt.truncatedTo(ChronoUnit.MICROS)));
      insert.setString(6, sourceType);
      insert.setObject(7, sourceId);
      insert.executeUpdate();
    }
  }

  private UUID insertCreditLedgerEntry(
      UUID userId,
      String entryType,
      String bucket,
      long amountDelta,
      Instant expiresAt,
      String sourceType,
      UUID sourceId,
      UUID reversalOfEntryId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO credit_ledger_entries (
                        user_id, entry_type, bucket, amount_delta, expires_at,
                        source_type, source_id, reversal_of_entry_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """)) {
      insert.setObject(1, userId);
      insert.setString(2, entryType);
      insert.setString(3, bucket);
      insert.setLong(4, amountDelta);
      if (expiresAt == null) insert.setTimestamp(5, null);
      else insert.setTimestamp(5, Timestamp.from(expiresAt));
      insert.setString(6, sourceType);
      insert.setObject(7, sourceId);
      insert.setObject(8, reversalOfEntryId);
      try (ResultSet row = insert.executeQuery()) {
        assertTrue(row.next());
        return row.getObject("id", UUID.class);
      }
    }
  }

  private long creditProjectedBalance(UUID userId, String bucket) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT COALESCE(SUM(balance), 0)
                    FROM credit_balance_projection
                    WHERE user_id = ? AND bucket = ?
                      AND (expires_at IS NULL OR expires_at > clock_timestamp())
                    """)) {
      select.setObject(1, userId);
      select.setString(2, bucket);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private long contentEntitlementCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM content_entitlements WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private long creditUnlockEntryCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM credit_ledger_entries "
                    + "WHERE user_id = ? AND source_type = 'CHAPTER_UNLOCK'")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private void updateCreditLedgerEntry(UUID entryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE credit_ledger_entries SET amount_delta = amount_delta WHERE id = ?")) {
      update.setObject(1, entryId);
      update.executeUpdate();
    }
  }

  private void deleteCreditLedgerEntry(UUID entryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM credit_ledger_entries WHERE id = ?")) {
      delete.setObject(1, entryId);
      delete.executeUpdate();
    }
  }

  @Test
  void publicationReviewerAssignmentsRequireAdminAndAppendAuditedIdempotentChanges()
      throws Exception {
    UUID adminId = createSessionTestUser("Synthetic role assignment admin");
    UUID targetId = createSessionTestUser("Synthetic publication reviewer");
    UUID outsiderId = createSessionTestUser("Synthetic role assignment outsider");
    try {
      insertBootstrapSecurityAdminFixture(adminId);
      SessionService.IssuedSession adminSession = sessionService.create(adminId).orElseThrow();
      SessionService.IssuedSession outsiderSession =
          sessionService.create(outsiderId).orElseThrow();
      var adminCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", adminSession.cookieValue());
      var outsiderCookie =
          new jakarta.servlet.http.Cookie("__Host-novel_session", outsiderSession.cookieValue());
      String adminCsrf = csrfTokenService.tokenFor(adminSession.cookieValue());
      String outsiderCsrf = csrfTokenService.tokenFor(outsiderSession.cookieValue());
      String listRoute = "/api/v1/admin/security/role-assignments";
      String searchRoute = "/api/v1/admin/security/users/search";
      String grantRoute = "/api/v1/admin/security/users/{userId}/roles/publication_reviewer";

      MvcResult currentAdmin =
          mockMvc.perform(get("/api/v1/auth/me").cookie(adminCookie)).andReturn();
      assertEquals(200, currentAdmin.getResponse().getStatus());
      assertEquals(
          "publication.reviewers.manage",
          objectMapper
              .readTree(currentAdmin.getResponse().getContentAsString())
              .path("roles")
              .get(1)
              .path("permissions")
              .get(0)
              .asText());
      MvcResult nonAdminCurrent =
          mockMvc.perform(get("/api/v1/auth/me").cookie(outsiderCookie)).andReturn();
      assertEquals(200, nonAdminCurrent.getResponse().getStatus());
      assertEquals(
          1,
          objectMapper
              .readTree(nonAdminCurrent.getResponse().getContentAsString())
              .path("roles")
              .size());

      MvcResult anonymous =
          mockMvc.perform(get(listRoute).param("role", "publication_reviewer")).andReturn();
      assertEquals(401, anonymous.getResponse().getStatus());
      assertTrue(anonymous.getResponse().getHeader("Cache-Control").contains("no-store"));

      MvcResult nonAdmin =
          mockMvc
              .perform(get(listRoute).param("role", "publication_reviewer").cookie(outsiderCookie))
              .andReturn();
      assertEquals(403, nonAdmin.getResponse().getStatus());
      assertTrue(nonAdmin.getResponse().getContentAsString().contains("ADMIN_REQUIRED"));
      assertTrue(nonAdmin.getResponse().getHeader("Cache-Control").contains("no-store"));

      MvcResult searchMissingCsrf =
          mockMvc
              .perform(
                  post(searchRoute)
                      .cookie(adminCookie)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"query\":\"session-\"}"))
              .andReturn();
      assertEquals(403, searchMissingCsrf.getResponse().getStatus());

      MvcResult eligibleSearch =
          mockMvc
              .perform(
                  post(searchRoute)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"query\":\"session-" + targetId + "@\"}"))
              .andReturn();
      assertEquals(200, eligibleSearch.getResponse().getStatus());
      assertTrue(eligibleSearch.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(eligibleSearch.getResponse().getContentAsString().contains(targetId.toString()));
      assertTrue(eligibleSearch.getResponse().getContentAsString().contains("s***"));
      assertFalse(
          eligibleSearch.getResponse().getContentAsString().contains("session-" + targetId));

      MvcResult shortSearch =
          mockMvc
              .perform(
                  post(searchRoute)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"query\":\"ab\"}"))
              .andReturn();
      assertEquals(400, shortSearch.getResponse().getStatus());

      MvcResult nonAdminSearch =
          mockMvc
              .perform(
                  post(searchRoute)
                      .cookie(outsiderCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, outsiderCsrf)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"query\":\"ses\"}"))
              .andReturn();
      assertEquals(403, nonAdminSearch.getResponse().getStatus());

      MvcResult missingCsrf =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header("Idempotency-Key", "reviewer-grant-no-csrf")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"REVIEW_CAPACITY\"}"))
              .andReturn();
      assertEquals(403, missingCsrf.getResponse().getStatus());

      MvcResult selfGrant =
          mockMvc
              .perform(
                  put(grantRoute, adminId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", "reviewer-self-grant-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"ROLE_CORRECTION\"}"))
              .andReturn();
      assertEquals(409, selfGrant.getResponse().getStatus());

      MvcResult unknownField =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", "reviewer-unknown-field-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"REVIEW_CAPACITY\",\"role\":\"system_admin\"}"))
              .andReturn();
      assertEquals(400, unknownField.getResponse().getStatus());

      String grantKey = "reviewer-grant-idempotent-001";
      MvcResult granted =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", grantKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"REVIEW_CAPACITY\"}"))
              .andReturn();
      assertEquals(201, granted.getResponse().getStatus());
      assertTrue(granted.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(granted.getResponse().getContentAsString().contains("GRANTED"));
      assertTrue(granted.getResponse().getContentAsString().contains(targetId.toString()));
      String assignmentId =
          objectMapper
              .readTree(granted.getResponse().getContentAsString())
              .path("assignment")
              .path("assignmentId")
              .asText();

      MvcResult replay =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", grantKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"REVIEW_CAPACITY\"}"))
              .andReturn();
      assertEquals(201, replay.getResponse().getStatus());
      assertTrue(replay.getResponse().getContentAsString().contains("\"replayed\":true"));
      assertTrue(replay.getResponse().getContentAsString().contains(assignmentId));

      MvcResult changedReplay =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", grantKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"TEMPORARY_COVERAGE\"}"))
              .andReturn();
      assertEquals(409, changedReplay.getResponse().getStatus());
      assertTrue(
          changedReplay.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));

      MvcResult duplicateGrant =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", "reviewer-grant-duplicate-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"TEMPORARY_COVERAGE\"}"))
              .andReturn();
      assertEquals(200, duplicateGrant.getResponse().getStatus());
      assertTrue(duplicateGrant.getResponse().getContentAsString().contains("ALREADY_ACTIVE"));
      assertEquals(1, countSecurityRoleEvents(targetId));

      MvcResult listed =
          mockMvc
              .perform(get(listRoute).param("role", "publication_reviewer").cookie(adminCookie))
              .andReturn();
      assertEquals(200, listed.getResponse().getStatus());
      assertTrue(listed.getResponse().getHeader("Cache-Control").contains("no-store"));
      assertTrue(listed.getResponse().getContentAsString().contains(targetId.toString()));
      assertTrue(listed.getResponse().getContentAsString().contains("maskedContactEmail"));
      assertFalse(listed.getResponse().getContentAsString().contains("session-" + targetId));
      var latestGrantEvent =
          objectMapper.readTree(listed.getResponse().getContentAsString()).path("items").get(0);
      assertEquals(
          latestGrantEvent.path("maskedContactEmail").asText(),
          latestGrantEvent.path("contactEmail").asText());
      assertNotNull(latestGrantEvent.path("lastEventId").asText());
      assertEquals("GRANTED", latestGrantEvent.path("lastEventActionCode").asText());
      assertEquals(adminId.toString(), latestGrantEvent.path("lastEventActorUserId").asText());
      assertEquals("REVIEW_CAPACITY", latestGrantEvent.path("lastEventReasonCode").asText());

      SessionService.IssuedSession reviewerSession = sessionService.create(targetId).orElseThrow();
      MvcResult reviewerDenied =
          mockMvc
              .perform(
                  get(listRoute)
                      .param("role", "publication_reviewer")
                      .cookie(
                          new jakarta.servlet.http.Cookie(
                              "__Host-novel_session", reviewerSession.cookieValue())))
              .andReturn();
      assertEquals(403, reviewerDenied.getResponse().getStatus());

      String revokeKey = "reviewer-revoke-idempotent-001";
      MvcResult revoked =
          mockMvc
              .perform(
                  delete(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", revokeKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"ACCESS_ENDED\"}"))
              .andReturn();
      assertEquals(204, revoked.getResponse().getStatus());
      assertTrue(revoked.getResponse().getHeader("Cache-Control").contains("no-store"));

      MvcResult revokeReplay =
          mockMvc
              .perform(
                  delete(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", revokeKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"ACCESS_ENDED\"}"))
              .andReturn();
      assertEquals(204, revokeReplay.getResponse().getStatus());
      assertEquals(2, countSecurityRoleEvents(targetId));
      assertEquals(0, countActiveReviewerAssignments(targetId));

      MvcResult afterRevoke =
          mockMvc
              .perform(get(listRoute).param("role", "publication_reviewer").cookie(adminCookie))
              .andReturn();
      assertEquals(200, afterRevoke.getResponse().getStatus());
      assertFalse(afterRevoke.getResponse().getContentAsString().contains(targetId.toString()));

      MvcResult revokedHistory =
          mockMvc
              .perform(
                  get(listRoute)
                      .param("role", "publication_reviewer")
                      .param("status", "revoked")
                      .cookie(adminCookie))
              .andReturn();
      assertEquals(200, revokedHistory.getResponse().getStatus());
      assertTrue(revokedHistory.getResponse().getContentAsString().contains(targetId.toString()));
      assertTrue(revokedHistory.getResponse().getContentAsString().contains("ACCESS_ENDED"));
      var latestRevocationEvent =
          objectMapper
              .readTree(revokedHistory.getResponse().getContentAsString())
              .path("items")
              .get(0);
      assertEquals("REVOKED", latestRevocationEvent.path("lastEventActionCode").asText());
      assertEquals(adminId.toString(), latestRevocationEvent.path("lastEventActorUserId").asText());

      MvcResult invalidStatus =
          mockMvc
              .perform(
                  get(listRoute)
                      .param("role", "publication_reviewer")
                      .param("status", "pending")
                      .cookie(adminCookie))
              .andReturn();
      assertEquals(400, invalidStatus.getResponse().getStatus());

      setTestUserStatus(targetId, "suspended");
      MvcResult inactiveTarget =
          mockMvc
              .perform(
                  put(grantRoute, targetId)
                      .cookie(adminCookie)
                      .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, adminCsrf)
                      .header("Idempotency-Key", "reviewer-inactive-target-001")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reasonCode\":\"REVIEW_CAPACITY\"}"))
              .andReturn();
      assertEquals(404, inactiveTarget.getResponse().getStatus());

      SQLException eventMutation =
          assertThrows(SQLException.class, () -> mutateSecurityRoleEvent(assignmentId));
      assertTrue(eventMutation.getMessage().contains("append-only"));
      assertThrows(SQLException.class, () -> deleteSecurityRoleReceipt(adminId, grantKey));
      assertThrows(SQLException.class, this::truncateSecurityRoleEvents);
      assertEquals(2, countSecurityRoleEvents(targetId));
    } finally {
      deleteSessionTestUser(adminId);
      deleteSessionTestUser(targetId);
      deleteSessionTestUser(outsiderId);
    }
  }

  private void insertBootstrapSecurityAdminFixture(UUID adminId) throws SQLException {
    UUID assignmentId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              """
                  INSERT INTO security_role_assignments
                      (id, user_id, role_code, assigned_by_user_id, reason_code)
                  VALUES (?, ?, 'system_admin', NULL, 'INITIAL_ADMIN_BOOTSTRAP')
                  """)) {
        insert.setObject(1, assignmentId);
        insert.setObject(2, adminId);
        assertEquals(1, insert.executeUpdate());
      }
      try (PreparedStatement event =
          connection.prepareStatement(
              """
                  INSERT INTO security_role_assignment_events
                      (id, assignment_id, target_user_id, role_code, action_code,
                       operator_identity, ticket_reference, reason_code)
                  VALUES (?, ?, ?, 'system_admin', 'BOOTSTRAPPED', 'test-fixture', 'TEST-BOOTSTRAP',
                          'INITIAL_ADMIN_BOOTSTRAP')
                  """)) {
        event.setObject(1, UUID.randomUUID());
        event.setObject(2, assignmentId);
        event.setObject(3, adminId);
        assertEquals(1, event.executeUpdate());
      }
    }
  }

  private int countSecurityRoleEvents(UUID targetUserId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM security_role_assignment_events WHERE target_user_id = ?")) {
      select.setObject(1, targetUserId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int countActiveReviewerAssignments(UUID targetUserId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT COUNT(*) FROM security_role_assignments
                    WHERE user_id = ? AND role_code = 'publication_reviewer' AND revoked_at IS NULL
                    """)) {
      select.setObject(1, targetUserId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private void mutateSecurityRoleEvent(String assignmentId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE security_role_assignment_events SET reason_code = reason_code WHERE assignment_id = ?")) {
      update.setObject(1, UUID.fromString(assignmentId));
      update.executeUpdate();
    }
  }

  private void truncateSecurityRoleEvents() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement truncate =
            connection.prepareStatement("TRUNCATE TABLE security_role_assignment_events")) {
      truncate.execute();
    }
  }

  private void setTestUserStatus(UUID userId, String status) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE users SET status = ? WHERE id = ?")) {
      update.setString(1, status);
      update.setObject(2, userId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void deleteSecurityRoleReceipt(UUID actorUserId, String idempotencyKey)
      throws SQLException {
    byte[] keyHash;
    try {
      keyHash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                    DELETE FROM security_role_assignment_idempotency
                    WHERE actor_user_id = ? AND idempotency_key_sha256 = ?
                    """)) {
      delete.setObject(1, actorUserId);
      delete.setBytes(2, keyHash);
      delete.executeUpdate();
    }
  }

  private UUID createSessionTestUser(String displayName) throws SQLException {
    UUID userId = UUID.randomUUID();
    String email = "session-" + userId + "@example.test";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
                     VALUES (?, ?, ?, clock_timestamp(), 'active')
                     """)) {
      insert.setObject(1, userId);
      insert.setString(2, displayName);
      insert.setString(3, email);
      assertEquals(1, insert.executeUpdate());
    }
    return userId;
  }

  private UUID insertCultivationXpLedger(
      UUID userId, UUID sessionId, int activeMinuteIndex, Instant activityAt) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO cultivation_xp_ledger
                        (user_id, source_session_id, active_minute_index, activity_at,
                         xp_delta, catalog_version, policy_version)
                    VALUES (?, ?, ?, ?, 1, 'cultivation-v1', 'xp-v1')
                    RETURNING id
                    """)) {
      insert.setObject(1, userId);
      insert.setObject(2, sessionId);
      insert.setInt(3, activeMinuteIndex);
      insert.setTimestamp(4, Timestamp.from(activityAt));
      try (ResultSet row = insert.executeQuery()) {
        assertTrue(row.next());
        return row.getObject("id", UUID.class);
      }
    }
  }

  private void insertCultivationTestReadingSession(
      UUID userId, UUID sessionId, ReaderHistoryFixture fixture, Instant startedAt, String startKey)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO cultivation_reading_sessions
                        (id, user_id, work_id, chapter_id, catalog_version, policy_version,
                         start_idempotency_key, start_request_sha256, started_at, last_heartbeat_at)
                    VALUES (?, ?, ?, ?, 'cultivation-v1', 'xp-v1', ?, ?, ?, ?)
                    """)) {
      insert.setObject(1, sessionId);
      insert.setObject(2, userId);
      insert.setObject(3, fixture.workId());
      insert.setObject(4, fixture.chapterId());
      insert.setString(5, startKey);
      insert.setBytes(6, new byte[32]);
      insert.setTimestamp(7, Timestamp.from(startedAt));
      insert.setTimestamp(8, Timestamp.from(startedAt));
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void closeCultivationTestReadingSession(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE cultivation_reading_sessions
                    SET state = 'closed', closed_at = clock_timestamp(),
                        last_heartbeat_at = clock_timestamp(), last_client_active = FALSE
                    WHERE id = ?
                    """)) {
      update.setObject(1, sessionId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private static String readingSessionStartBody(ReaderHistoryFixture fixture) {
    return "{\"workId\":\""
        + fixture.workId()
        + "\",\"chapterId\":\""
        + fixture.chapterId()
        + "\",\"active\":true}";
  }

  private MvcResult postCultivationHeartbeat(
      jakarta.servlet.http.Cookie cookie,
      String csrfToken,
      UUID sessionId,
      String idempotencyKey,
      String body)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/me/reading-sessions/{sessionId}:heartbeat", sessionId)
                .cookie(cookie)
                .header(SessionCsrfTokenService.TOKEN_HEADER_NAME, csrfToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private void setCultivationReadingSessionAge(
      UUID sessionId, long heartbeatAgeSeconds, long startedAgeSeconds) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE cultivation_reading_sessions
                    SET started_at = clock_timestamp() - (? * INTERVAL '1 second'),
                        last_heartbeat_at = clock_timestamp() - (? * INTERVAL '1 second')
                    WHERE id = ?
                    """)) {
      update.setLong(1, startedAgeSeconds);
      update.setLong(2, heartbeatAgeSeconds);
      update.setObject(3, sessionId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void setCultivationReadingSessionActiveAge(
      UUID sessionId, long heartbeatAgeSeconds, long startedAgeSeconds) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE cultivation_reading_sessions
                    SET started_at = clock_timestamp() - (? * INTERVAL '1 second'),
                        last_heartbeat_at = clock_timestamp() - (? * INTERVAL '1 second'),
                        last_client_active = TRUE
                    WHERE id = ?
                    """)) {
      update.setLong(1, startedAgeSeconds);
      update.setLong(2, heartbeatAgeSeconds);
      update.setObject(3, sessionId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private long sessionActiveSeconds(UUID sessionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT total_active_seconds FROM cultivation_reading_sessions WHERE id = ?")) {
      select.setObject(1, sessionId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong("total_active_seconds");
      }
    }
  }

  private void rebuildCultivationDailyXp(UUID userId, LocalDate activityDate) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement rebuild =
            connection.prepareStatement("SELECT rebuild_cultivation_daily_xp_aggregate(?, ?)")) {
      rebuild.setObject(1, userId);
      rebuild.setObject(2, java.sql.Date.valueOf(activityDate));
      rebuild.execute();
    }
  }

  private LocalDate cultivationXpActivityDate(UUID ledgerEntryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT activity_date FROM cultivation_xp_ledger WHERE id = ?")) {
      select.setObject(1, ledgerEntryId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getDate("activity_date").toLocalDate();
      }
    }
  }

  private long cultivationDailyXp(UUID userId, LocalDate activityDate) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT xp_earned FROM cultivation_daily_xp_aggregates "
                    + "WHERE user_id = ? AND activity_date = ?")) {
      select.setObject(1, userId);
      select.setObject(2, java.sql.Date.valueOf(activityDate));
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getLong("xp_earned") : 0L;
      }
    }
  }

  private long cultivationDailyLedgerCount(UUID userId, LocalDate activityDate)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT ledger_entry_count FROM cultivation_daily_xp_aggregates "
                    + "WHERE user_id = ? AND activity_date = ?")) {
      select.setObject(1, userId);
      select.setObject(2, java.sql.Date.valueOf(activityDate));
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getLong("ledger_entry_count") : 0L;
      }
    }
  }

  private void insertTestVolume(UUID volumeId, UUID workId, int ordinal, String title)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO volumes (id, work_id, ordinal, title) VALUES (?, ?, ?, ?)
                     """)) {
      insert.setObject(1, volumeId);
      insert.setObject(2, workId);
      insert.setInt(3, ordinal);
      insert.setString(4, title);
      insert.executeUpdate();
    }
  }

  private void insertTestChapter(
      UUID chapterId, UUID workId, UUID volumeId, int ordinal, String title) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language)
                     VALUES (?, ?, ?, ?, ?, 'und')
                     """)) {
      insert.setObject(1, chapterId);
      insert.setObject(2, workId);
      insert.setObject(3, volumeId);
      insert.setInt(4, ordinal);
      insert.setString(5, title);
      insert.executeUpdate();
    }
  }

  private int segmentCount(UUID revisionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM segments WHERE chapter_revision_id = ?")) {
      select.setObject(1, revisionId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int revisionCount(UUID chapterId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM chapter_revisions WHERE chapter_id = ?")) {
      select.setObject(1, chapterId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private void setWorkCreatedAt(UUID workId, Instant createdAt) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE works SET created_at = ? WHERE id = ?")) {
      update.setTimestamp(1, Timestamp.from(createdAt));
      update.setObject(2, workId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void setWorkStatus(UUID workId, String status) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE works SET status = ? WHERE id = ?")) {
      update.setString(1, status);
      update.setObject(2, workId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void deleteLibraryTestData(UUID ownerId) throws SQLException {
    deleteAuthoredWorkTestArtifacts(ownerId);
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteProgress =
          connection.prepareStatement(
              "DELETE FROM reader_progress p USING works w "
                  + "WHERE p.work_id = w.id AND w.owner_user_id = ?")) {
        deleteProgress.setObject(1, ownerId);
        deleteProgress.executeUpdate();
      }
      try (PreparedStatement deleteWorkTags =
          connection.prepareStatement(
              "DELETE FROM work_tags WHERE owner_user_id = ? OR assigned_by_user_id = ?")) {
        deleteWorkTags.setObject(1, ownerId);
        deleteWorkTags.setObject(2, ownerId);
        deleteWorkTags.executeUpdate();
      }
      try (PreparedStatement deleteSegments =
          connection.prepareStatement(
              """
                    DELETE FROM segments s USING chapter_revisions r, chapters c, works w
                    WHERE s.chapter_revision_id = r.id AND r.chapter_id = c.id
                      AND c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
        deleteSegments.setObject(1, ownerId);
        deleteSegments.executeUpdate();
      }
      try (PreparedStatement clearCurrentRevision =
          connection.prepareStatement(
              """
                    UPDATE chapters c SET source_revision_id = NULL FROM works w
                    WHERE c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
        clearCurrentRevision.setObject(1, ownerId);
        clearCurrentRevision.executeUpdate();
      }
      try (PreparedStatement deleteRevisions =
          connection.prepareStatement(
              """
                    DELETE FROM chapter_revisions r USING chapters c, works w
                    WHERE r.chapter_id = c.id AND c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
        deleteRevisions.setObject(1, ownerId);
        deleteRevisions.executeUpdate();
      }
      try (PreparedStatement deleteChapters =
          connection.prepareStatement(
              """
                    DELETE FROM chapters c USING works w
                    WHERE c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
        deleteChapters.setObject(1, ownerId);
        deleteChapters.executeUpdate();
      }
      try (PreparedStatement deleteVolumes =
          connection.prepareStatement(
              """
                    DELETE FROM volumes v USING works w
                    WHERE v.work_id = w.id AND w.owner_user_id = ?
                    """)) {
        deleteVolumes.setObject(1, ownerId);
        deleteVolumes.executeUpdate();
      }
      try (PreparedStatement deleteAssets =
          connection.prepareStatement("DELETE FROM assets WHERE owner_user_id = ?")) {
        deleteAssets.setObject(1, ownerId);
        deleteAssets.executeUpdate();
      }
      try (PreparedStatement deleteTags =
          connection.prepareStatement("DELETE FROM tags WHERE owner_user_id = ?")) {
        deleteTags.setObject(1, ownerId);
        deleteTags.executeUpdate();
      }
      try (PreparedStatement deleteWorks =
          connection.prepareStatement("DELETE FROM works WHERE owner_user_id = ?")) {
        deleteWorks.setObject(1, ownerId);
        deleteWorks.executeUpdate();
      }
      try (PreparedStatement deleteLibraries =
          connection.prepareStatement("DELETE FROM libraries WHERE owner_user_id = ?")) {
        deleteLibraries.setObject(1, ownerId);
        deleteLibraries.executeUpdate();
      }
    }
  }

  private void deleteAuthoredWorkTestArtifacts(UUID ownerId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_acceptance_idempotency"
                  + " DISABLE TRIGGER trg_publication_rights_idempotency_immutable");
          disable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " DISABLE TRIGGER trg_publication_rights_acceptance_immutable");
        }
        try (PreparedStatement deleteIdempotency =
            connection.prepareStatement(
                """
                    DELETE FROM publication_rights_policy_acceptance_idempotency
                    WHERE work_id IN (SELECT id FROM works WHERE owner_user_id = ?)
                    """)) {
          deleteIdempotency.setObject(1, ownerId);
          deleteIdempotency.executeUpdate();
        }
        try (PreparedStatement deleteAcceptances =
            connection.prepareStatement(
                "DELETE FROM publication_rights_policy_acceptances"
                    + " WHERE work_id IN (SELECT id FROM works WHERE owner_user_id = ?)")) {
          deleteAcceptances.setObject(1, ownerId);
          deleteAcceptances.executeUpdate();
        }
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " ENABLE TRIGGER trg_publication_rights_acceptance_immutable");
          enable.execute(
              "ALTER TABLE publication_rights_policy_acceptance_idempotency"
                  + " ENABLE TRIGGER trg_publication_rights_idempotency_immutable");
        }
        for (String table :
            List.of(
                "authoring_chapter_revision_writes",
                "authoring_chapter_creations",
                "authoring_manuscript_creations")) {
          try (PreparedStatement delete =
              connection.prepareStatement(
                  "DELETE FROM "
                      + table
                      + " WHERE work_id IN "
                      + "(SELECT id FROM works WHERE owner_user_id = ?)")) {
            delete.setObject(1, ownerId);
            delete.executeUpdate();
          }
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private void deleteWorkspaceTestData(UUID workspaceId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement deleteWorkspace =
            connection.prepareStatement("DELETE FROM workspaces WHERE id = ?")) {
      deleteWorkspace.setObject(1, workspaceId);
      deleteWorkspace.executeUpdate();
    }
  }

  private void addSessionTestGoogleIdentity(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO external_identities (
                         id, user_id, provider, issuer, subject, provider_email, provider_email_verified)
                     VALUES (?, ?, 'google', 'https://accounts.google.com', ?, ?, TRUE)
                     """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setString(3, "current-user-" + userId);
      insert.setString(4, "session-" + userId + "@example.test");
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void addCurrentPolicyAcceptances(UUID userId) throws SQLException {
    addPolicyAcceptance(userId, "TERMS", "terms-current-1", "TERMS_ACCEPTED");
    addPolicyAcceptance(
        userId, "PRIVACY_NOTICE", "privacy-current-1", "PRIVACY_NOTICE_ACKNOWLEDGED");
  }

  private void insertPublicationRightsPolicyAcceptance(
      UUID acceptanceId,
      UUID userId,
      UUID workId,
      UUID idempotencyKey,
      String policyVersion,
      byte[] policyDigest)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO publication_rights_policy_acceptances
                        (id, user_id, work_id, idempotency_key, policy_version, policy_sha256, source)
                    VALUES (?, ?, ?, ?, ?, ?, 'web-authoring-publication')
                    """)) {
      insert.setObject(1, acceptanceId);
      insert.setObject(2, userId);
      insert.setObject(3, workId);
      insert.setObject(4, idempotencyKey);
      insert.setString(5, policyVersion);
      insert.setBytes(6, policyDigest);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private int publicationRightsPolicyAcceptanceCount(UUID userId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM publication_rights_policy_acceptances"
                    + " WHERE user_id = ? AND work_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private int publicationRightsPolicyIdempotencyCount(UUID userId, UUID workId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM publication_rights_policy_acceptance_idempotency"
                    + " WHERE user_id = ? AND work_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private void deletePublicationRightsPolicyAcceptanceFixture(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_acceptance_idempotency"
                  + " DISABLE TRIGGER trg_publication_rights_idempotency_immutable");
        }
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM publication_rights_policy_acceptance_idempotency WHERE work_id = ?")) {
          delete.setObject(1, workId);
          delete.executeUpdate();
        }
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_acceptance_idempotency"
                  + " ENABLE TRIGGER trg_publication_rights_idempotency_immutable");
        }
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " DISABLE TRIGGER trg_publication_rights_acceptance_immutable");
        }
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM publication_rights_policy_acceptances WHERE work_id = ?")) {
          delete.setObject(1, workId);
          delete.executeUpdate();
        }
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " ENABLE TRIGGER trg_publication_rights_acceptance_immutable");
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private void deletePublicationRightsPolicyFixture(String policyVersion) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_documents"
                  + " DISABLE TRIGGER trg_publication_rights_policy_immutable");
        }
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM publication_rights_policy_documents WHERE version = ?")) {
          delete.setString(1, policyVersion);
          delete.executeUpdate();
        }
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_documents"
                  + " ENABLE TRIGGER trg_publication_rights_policy_immutable");
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private void addPolicyAcceptance(UUID userId, String type, String version, String decision)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, source)
                     VALUES (?, ?, ?, ?, ?, 'web')
                     """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setString(3, type);
      insert.setString(4, version);
      insert.setString(5, decision);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertPolicyDocument(
      String type, String version, int effectiveDayOffset, String document) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO policy_documents (policy_type, version, effective_at, document_markdown)
                     VALUES (?, ?, clock_timestamp() + (? * INTERVAL '1 day'), ?)
                     """)) {
      insert.setString(1, type);
      insert.setString(2, version);
      insert.setInt(3, effectiveDayOffset);
      insert.setString(4, document);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertImportRightsStatement(
      String version, String text, UUID approverId, String status, int effectiveDayOffset)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_rights_statements
                         (version, effective_at, display_text, status, approved_at, approved_by)
                     VALUES (?, clock_timestamp() + (? * INTERVAL '1 day'), ?, ?,
                             CASE WHEN ? = 'APPROVED' THEN clock_timestamp() END, ?)
                     """)) {
      insert.setString(1, version);
      insert.setInt(2, effectiveDayOffset);
      insert.setString(3, text);
      insert.setString(4, status);
      insert.setString(5, status);
      insert.setObject(6, approverId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertImportRightsAcceptance(
      UUID userId, UUID receiptId, String version, String text)
      throws SQLException, NoSuchAlgorithmException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_rights_acceptances
                         (id, user_id, idempotency_key, statement_version, statement_sha256, source)
                     VALUES (?, ?, ?, ?, ?, 'web-import-flow')
                     """)) {
      insert.setObject(1, receiptId);
      insert.setObject(2, userId);
      insert.setObject(3, UUID.randomUUID());
      insert.setString(4, version);
      insert.setBytes(5, sha256(text));
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertImportJobFixture(
      UUID jobId,
      UUID userId,
      UUID libraryId,
      UUID receiptId,
      String version,
      String status,
      byte[] content,
      int expiryDaysAgo)
      throws SQLException, NoSuchAlgorithmException {
    Instant now = Instant.now();
    Instant expiresAt =
        expiryDaysAgo > 0
            ? now.minusSeconds((long) expiryDaysAgo * 86_400)
            : now.plusSeconds(3_600);
    Instant createdAt = expiryDaysAgo > 0 ? expiresAt.minusSeconds(172_800) : now;
    Instant tokenExpiresAt = expiresAt.minusSeconds(1_800);
    Instant uploadedAt = createdAt.plusSeconds(5);
    String text = "Synthetic test-only import retention statement.";
    insertImportRightsAcceptanceAt(
        userId, receiptId, version, text, createdAt.minusSeconds(60), createdAt.minusSeconds(30));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_jobs (
                         id, user_id, library_id, rights_acceptance_id, rights_statement_version,
                         original_filename, declared_content_type, declared_size_bytes, storage_key,
                         upload_token_sha256, upload_token_expires_at, status, created_at,
                         rights_confirmed_at, expires_at, uploaded_at, actual_size_bytes, content_sha256)
                     VALUES (?, ?, ?, ?, ?, 'retention-test.txt', 'text/plain', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
      insert.setObject(1, jobId);
      insert.setObject(2, userId);
      insert.setObject(3, libraryId);
      insert.setObject(4, receiptId);
      insert.setString(5, version);
      insert.setLong(6, content.length);
      insert.setString(7, "staging/" + jobId + ".upload");
      insert.setBytes(
          8,
          "upload_pending".equals(status)
              ? sha256(("cleanup-" + jobId).getBytes(StandardCharsets.US_ASCII))
              : null);
      insert.setObject(
          9,
          "upload_pending".equals(status)
              ? OffsetDateTime.ofInstant(tokenExpiresAt, ZoneOffset.UTC)
              : null);
      insert.setString(10, status);
      insert.setObject(11, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(12, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(13, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
      insert.setObject(
          14,
          "uploaded".equals(status) ? OffsetDateTime.ofInstant(uploadedAt, ZoneOffset.UTC) : null);
      insert.setObject(15, "uploaded".equals(status) ? (long) content.length : null);
      insert.setBytes(16, "uploaded".equals(status) ? sha256(content) : null);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertImportRightsAcceptanceAt(
      UUID userId,
      UUID receiptId,
      String version,
      String text,
      Instant acceptedAt,
      Instant consumedAt)
      throws SQLException, NoSuchAlgorithmException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_rights_acceptances (
                         id, user_id, idempotency_key, statement_version, statement_sha256,
                         accepted_at, consumed_at, source)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 'web-import-flow')
                     """)) {
      insert.setObject(1, receiptId);
      insert.setObject(2, userId);
      insert.setObject(3, UUID.randomUUID());
      insert.setString(4, version);
      insert.setBytes(5, sha256(text));
      insert.setObject(6, OffsetDateTime.ofInstant(acceptedAt, ZoneOffset.UTC));
      insert.setObject(7, OffsetDateTime.ofInstant(consumedAt, ZoneOffset.UTC));
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void deleteImportRightsTestStatement(String version) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // The disposable integration database removes only synthetic approved content during
        // teardown.
        try (PreparedStatement disable =
            connection.prepareStatement(
                "ALTER TABLE import_rights_statements DISABLE TRIGGER trg_import_rights_statement_immutable")) {
          disable.execute();
        }
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM import_rights_statements WHERE version = ?")) {
          delete.setString(1, version);
          delete.executeUpdate();
        }
        try (PreparedStatement enable =
            connection.prepareStatement(
                "ALTER TABLE import_rights_statements ENABLE TRIGGER trg_import_rights_statement_immutable")) {
          enable.execute();
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private void deleteImportRightsTestAcceptances(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement disable =
            connection.prepareStatement(
                "ALTER TABLE import_rights_acceptances DISABLE TRIGGER trg_import_rights_acceptance_immutable")) {
          disable.execute();
        }
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM import_rights_acceptances WHERE user_id = ?")) {
          delete.setObject(1, userId);
          delete.executeUpdate();
        }
        try (PreparedStatement enable =
            connection.prepareStatement(
                "ALTER TABLE import_rights_acceptances ENABLE TRIGGER trg_import_rights_acceptance_immutable")) {
          enable.execute();
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private String importCreateJson(
      UUID libraryId,
      UUID receiptId,
      String version,
      String filename,
      String contentType,
      int sizeBytes,
      Boolean rightsConfirmed)
      throws Exception {
    return importCreateJson(
        libraryId, receiptId, version, filename, contentType, sizeBytes, rightsConfirmed, Map.of());
  }

  private String importCreateJson(
      UUID libraryId,
      UUID receiptId,
      String version,
      String filename,
      String contentType,
      int sizeBytes,
      Boolean rightsConfirmed,
      Map<String, Object> extraFields)
      throws Exception {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("libraryId", libraryId);
    requestBody.put(
        "source",
        Map.of(
            "kind",
            "upload",
            "filename",
            filename,
            "contentType",
            contentType,
            "sizeBytes",
            sizeBytes));
    requestBody.put("rightsAcceptanceId", receiptId);
    requestBody.put("rightsStatementVersion", version);
    requestBody.put("rightsConfirmed", rightsConfirmed);
    requestBody.putAll(extraFields);
    return objectMapper.writeValueAsString(requestBody);
  }

  private void insertUploadedEpubImportFixture(
      UUID jobId,
      UUID userId,
      UUID libraryId,
      UUID receiptId,
      String version,
      String statementText,
      byte[] content,
      int expiryDaysAgo)
      throws SQLException, NoSuchAlgorithmException {
    Instant now = Instant.now();
    Instant expiresAt =
        expiryDaysAgo > 0 ? now.minusSeconds((long) expiryDaysAgo * 86_400) : now.plusSeconds(3600);
    Instant createdAt = expiresAt.minusSeconds(expiryDaysAgo > 0 ? 86_400 : 60);
    Instant acceptedAt = createdAt.minusSeconds(60);
    insertImportRightsAcceptanceAt(
        userId, receiptId, version, statementText, acceptedAt, acceptedAt.plusSeconds(10));
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_jobs (
                         id, user_id, library_id, rights_acceptance_id, rights_statement_version,
                         original_filename, declared_content_type, declared_size_bytes, storage_key,
                         status, created_at, rights_confirmed_at, expires_at, uploaded_at,
                         actual_size_bytes, content_sha256)
                     VALUES (?, ?, ?, ?, ?, 'private-parse-test.epub', 'application/epub+zip', ?, ?,
                             'uploaded', ?, ?, ?, ?, ?, ?)
                     """)) {
      insert.setObject(1, jobId);
      insert.setObject(2, userId);
      insert.setObject(3, libraryId);
      insert.setObject(4, receiptId);
      insert.setString(5, version);
      insert.setLong(6, content.length);
      insert.setString(7, "staging/" + jobId + ".upload");
      insert.setObject(8, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(9, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(10, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
      insert.setObject(11, OffsetDateTime.ofInstant(createdAt.plusSeconds(5), ZoneOffset.UTC));
      insert.setLong(12, content.length);
      insert.setBytes(13, sha256(content));
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertQueuedParseJobFixture(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_parse_jobs (import_id, status, attempt, parser_version)
                     VALUES (?, 'queued', 0, 'epub3-v1')
                     """)) {
      insert.setObject(1, importId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertParsingParseJobFixture(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_parse_jobs
                         (import_id, status, attempt, parser_version, lease_expires_at)
                     VALUES (?, 'parsing', 1, 'txt-v1', clock_timestamp() + INTERVAL '1 minute')
                     """)) {
      insert.setObject(1, importId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertUploadedImportFixture(
      UUID jobId,
      UUID userId,
      UUID libraryId,
      UUID receiptId,
      String version,
      String statementText,
      String filename,
      String contentType,
      byte[] content)
      throws SQLException, NoSuchAlgorithmException {
    Instant createdAt = Instant.now().minusSeconds(60);
    Instant expiresAt = Instant.now().plusSeconds(3_600);
    Instant acceptedAt = createdAt.minusSeconds(60);
    insertImportRightsAcceptanceAt(
        userId, receiptId, version, statementText, acceptedAt, acceptedAt.plusSeconds(10));
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO import_jobs (
                         id, user_id, library_id, rights_acceptance_id, rights_statement_version,
                         original_filename, declared_content_type, declared_size_bytes, storage_key,
                         status, created_at, rights_confirmed_at, expires_at, uploaded_at,
                         actual_size_bytes, content_sha256)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                             'uploaded', ?, ?, ?, ?, ?, ?)
                     """)) {
      insert.setObject(1, jobId);
      insert.setObject(2, userId);
      insert.setObject(3, libraryId);
      insert.setObject(4, receiptId);
      insert.setString(5, version);
      insert.setString(6, filename);
      insert.setString(7, contentType);
      insert.setLong(8, content.length);
      insert.setString(9, "staging/" + jobId + ".upload");
      insert.setObject(10, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(11, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
      insert.setObject(12, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
      insert.setObject(13, OffsetDateTime.ofInstant(createdAt.plusSeconds(5), ZoneOffset.UTC));
      insert.setLong(14, content.length);
      insert.setBytes(15, sha256(content));
      assertEquals(1, insert.executeUpdate());
    }
  }

  private static byte[] tinyEpub(String title, String... chapterTitleAndBodies) throws IOException {
    if (chapterTitleAndBodies.length == 0 || chapterTitleAndBodies.length % 2 != 0) {
      throw new IllegalArgumentException("Provide title/body pairs for the EPUB spine");
    }
    String container =
        "<container><rootfiles><rootfile full-path=\"package.opf\" "
            + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
    String packageDocument =
        "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" "
            + "unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>"
            + title
            + "</dc:title><dc:identifier id=\"uid\">urn:test</dc:identifier>"
            + "<dc:creator>An Author</dc:creator></metadata><manifest>";
    StringBuilder spine = new StringBuilder("<spine>");
    List<String> chapterDocuments = new ArrayList<>();
    Map<String, String> chapterPathsByContent = new HashMap<>();
    for (int index = 0; index < chapterTitleAndBodies.length; index += 2) {
      String chapterTitle = chapterTitleAndBodies[index];
      String chapterBody = chapterTitleAndBodies[index + 1];
      String key = chapterTitle + "\u0000" + chapterBody;
      String id = chapterPathsByContent.get(key);
      if (id == null) {
        id = "chapter" + (index / 2);
        String path = id + ".xhtml";
        chapterPathsByContent.put(key, id);
        packageDocument +=
            "<item id=\"" + id + "\" href=\"" + path + "\" media-type=\"application/xhtml+xml\"/>";
        chapterDocuments.add(
            path
                + "\u0000<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>"
                + chapterTitle
                + "</title></head><body><h1>"
                + chapterTitle
                + "</h1><p>"
                + chapterBody
                + "</p></body></html>");
      }
      spine.append("<itemref idref=\"").append(id).append("\"/>");
    }
    packageDocument += "</manifest>" + spine + "</spine></package>";
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream archive = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      writeStoredZipEntry(
          archive, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
      writeDeflatedZipEntry(archive, "META-INF/container.xml", container);
      writeDeflatedZipEntry(archive, "package.opf", packageDocument);
      for (String chapterDocument : chapterDocuments) {
        int separator = chapterDocument.indexOf('\u0000');
        writeDeflatedZipEntry(
            archive,
            chapterDocument.substring(0, separator),
            chapterDocument.substring(separator + 1));
      }
      archive.finish();
      return bytes.toByteArray();
    }
  }

  private static void writeStoredZipEntry(ZipOutputStream archive, String name, byte[] value)
      throws IOException {
    CRC32 crc = new CRC32();
    crc.update(value);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(value.length);
    entry.setCompressedSize(value.length);
    entry.setCrc(crc.getValue());
    archive.putNextEntry(entry);
    archive.write(value);
    archive.closeEntry();
  }

  private static void writeDeflatedZipEntry(ZipOutputStream archive, String name, String value)
      throws IOException {
    archive.putNextEntry(new ZipEntry(name));
    archive.write(value.getBytes(StandardCharsets.UTF_8));
    archive.closeEntry();
  }

  private JsonNode awaitImportStatus(
      jakarta.servlet.http.Cookie cookie, UUID importId, String expectedStatus) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    JsonNode latest = null;
    while (System.nanoTime() < deadline) {
      MvcResult result =
          mockMvc.perform(get("/api/v1/imports/{id}", importId).cookie(cookie)).andReturn();
      assertEquals(200, result.getResponse().getStatus());
      latest = objectMapper.readTree(result.getResponse().getContentAsString());
      if (expectedStatus.equals(latest.path("status").asText())) return latest;
      importParseService.processNext();
      Thread.sleep(50);
    }
    throw new AssertionError(
        "Import did not reach status " + expectedStatus + "; latest=" + latest);
  }

  private int importParseJobCount(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int importParseAttempt(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT attempt FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private String importParseParserVersion(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT parser_version FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String importParsePreviewJson(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT preview_data::text FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private int importPreviewVersion(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT preview_version FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private String importParseJobStatus(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT status FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private int importParsePreviewByteCount(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT COALESCE(octet_length(preview_data::text), 0)
                     FROM import_parse_jobs WHERE import_id = ?
                     """)) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int importParseEtagBytes(UUID importId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT octet_length(preview_etag) FROM import_parse_jobs WHERE import_id = ?")) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int workCountForLibrary(UUID libraryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT COUNT(*) FROM works WHERE library_id = ?")) {
      select.setObject(1, libraryId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int authoredWorkCount(UUID ownerId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM works WHERE owner_user_id = ? AND source_kind = 'authored'")) {
      select.setObject(1, ownerId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int bookmarkCount(UUID userId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM reader_bookmarks WHERE user_id = ? AND work_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private static String tamperCursor(String cursor) {
    String replacement = cursor.endsWith("A") ? "B" : "A";
    return cursor.substring(0, cursor.length() - 1) + replacement;
  }

  private int annotationCount(UUID userId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM reader_annotations WHERE user_id = ? AND work_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int chapterCountForLibrary(UUID libraryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT COUNT(*) FROM chapters WHERE work_id IN
                         (SELECT id FROM works WHERE library_id = ?)
                     """)) {
      select.setObject(1, libraryId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int revisionCountForWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT COUNT(*) FROM chapter_revisions r
                     JOIN chapters c ON c.id = r.chapter_id
                     WHERE c.work_id = ?
                     """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int segmentCountForWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT COUNT(*) FROM segments s
                     JOIN chapter_revisions r ON r.id = s.chapter_revision_id
                     JOIN chapters c ON c.id = r.chapter_id
                     WHERE c.work_id = ?
                     """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private String revisionSourceFormatForWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT r.source_format FROM chapter_revisions r
                     JOIN chapters c ON c.id = r.chapter_id
                     WHERE c.work_id = ?
                     ORDER BY c.ordinal
                     LIMIT 1
                     """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String firstSegmentAnchorForWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT s.stable_anchor FROM segments s
                     JOIN chapter_revisions r ON r.id = s.chapter_revision_id
                     JOIN chapters c ON c.id = r.chapter_id
                     WHERE c.work_id = ?
                     ORDER BY c.ordinal, s.ordinal
                     LIMIT 1
                     """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String revisionContentForWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT r.normalized_content FROM chapter_revisions r
                     JOIN chapters c ON c.id = r.chapter_id
                     WHERE c.work_id = ?
                     ORDER BY c.ordinal
                     """)) {
      select.setObject(1, workId);
      try (ResultSet rows = select.executeQuery()) {
        StringBuilder content = new StringBuilder();
        while (rows.next()) content.append(rows.getString(1)).append('\n');
        return content.toString();
      }
    }
  }

  private void createFailImportCommitSegmentsTrigger() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
                    CREATE FUNCTION test_fail_import_commit_segment_insert() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic import commit rollback'; END; $$
                    """);
      statement.execute(
          """
                    CREATE TRIGGER test_fail_import_commit_segment_insert
                    BEFORE INSERT ON segments FOR EACH ROW
                    EXECUTE FUNCTION test_fail_import_commit_segment_insert()
                    """);
    }
  }

  private void dropFailImportCommitSegmentsTrigger() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TRIGGER IF EXISTS test_fail_import_commit_segment_insert ON segments");
      statement.execute("DROP FUNCTION IF EXISTS test_fail_import_commit_segment_insert()");
    }
  }

  private void deleteImportedWorksForLibrary(UUID libraryId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String workIds = "SELECT id FROM works WHERE library_id = ?";
        try (PreparedStatement deleteSegments =
            connection.prepareStatement(
                """
                        DELETE FROM segments WHERE chapter_revision_id IN (
                            SELECT r.id FROM chapter_revisions r
                            JOIN chapters c ON c.id = r.chapter_id
                            JOIN works w ON w.id = c.work_id
                            WHERE w.library_id = ?)
                        """)) {
          deleteSegments.setObject(1, libraryId);
          deleteSegments.executeUpdate();
        }
        try (PreparedStatement clearCurrentRevision =
            connection.prepareStatement(
                """
                        UPDATE chapters SET source_revision_id = NULL
                        WHERE work_id IN ("""
                    + workIds
                    + ")")) {
          clearCurrentRevision.setObject(1, libraryId);
          clearCurrentRevision.executeUpdate();
        }
        try (PreparedStatement deleteRevisions =
            connection.prepareStatement(
                """
                        DELETE FROM chapter_revisions WHERE chapter_id IN (
                            SELECT c.id FROM chapters c JOIN works w ON w.id = c.work_id
                            WHERE w.library_id = ?)
                        """)) {
          deleteRevisions.setObject(1, libraryId);
          deleteRevisions.executeUpdate();
        }
        try (PreparedStatement deleteChapters =
            connection.prepareStatement(
                """
                        DELETE FROM chapters WHERE work_id IN ("""
                    + workIds
                    + ")")) {
          deleteChapters.setObject(1, libraryId);
          deleteChapters.executeUpdate();
        }
        try (PreparedStatement deleteWorks =
            connection.prepareStatement("DELETE FROM works WHERE library_id = ?")) {
          deleteWorks.setObject(1, libraryId);
          deleteWorks.executeUpdate();
        }
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private int importJobCount() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM import_jobs");
        ResultSet row = select.executeQuery()) {
      assertTrue(row.next());
      return row.getInt(1);
    }
  }

  private int importJobCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT COUNT(*) FROM import_jobs WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private UUID importJobOwner(UUID jobId) throws SQLException {
    return importJobUuid(jobId, "user_id");
  }

  private UUID importJobLibrary(UUID jobId) throws SQLException {
    return importJobUuid(jobId, "library_id");
  }

  private UUID importJobUuid(UUID jobId, String column) throws SQLException {
    if (!Set.of("user_id", "library_id").contains(column))
      throw new IllegalArgumentException("column");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT " + column + " FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getObject(1, UUID.class);
      }
    }
  }

  private String importJobFilename(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT original_filename FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String importJobStorageKey(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT storage_key FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private byte[] importJobTokenHash(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT upload_token_sha256 FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBytes(1);
      }
    }
  }

  private String importJobStatus(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT status FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private OffsetDateTime importJobStagingFileDeletedAt(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT staging_file_deleted_at FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getObject(1, OffsetDateTime.class);
      }
    }
  }

  private byte[] importJobContentHash(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT content_sha256 FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBytes(1);
      }
    }
  }

  private long importJobActualSize(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT actual_size_bytes FROM import_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private Optional<Instant> importRightsAcceptanceConsumedAt(UUID receiptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT consumed_at FROM import_rights_acceptances WHERE id = ?")) {
      select.setObject(1, receiptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        OffsetDateTime consumedAt = row.getObject(1, OffsetDateTime.class);
        return consumedAt == null ? Optional.empty() : Optional.of(consumedAt.toInstant());
      }
    }
  }

  private long stagedUploadCount() throws java.io.IOException {
    Path root = Path.of(System.getProperty("java.io.tmpdir"), "novel-web-import-postgres-tests");
    if (!Files.isDirectory(root)) return 0;
    try (var files = Files.list(root)) {
      return files.filter(path -> path.getFileName().toString().endsWith(".upload")).count();
    }
  }

  private void deleteImportJobs(UUID userId) throws SQLException {
    List<UUID> jobs = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT id FROM import_jobs WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) jobs.add(rows.getObject(1, UUID.class));
      }
    }
    for (UUID jobId : jobs) {
      try {
        importStagingFileStore.delete(jobId);
      } catch (java.io.IOException exception) {
        throw new SQLException("Unable to clean import integration fixture", exception);
      }
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM import_jobs WHERE user_id = ?")) {
      delete.setObject(1, userId);
      delete.executeUpdate();
    }
  }

  private void deleteLibrary(UUID libraryId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
      delete.setObject(1, libraryId);
      delete.executeUpdate();
    }
  }

  private int importRightsAcceptanceCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM import_rights_acceptances WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private int policyAcceptanceCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM policy_acceptances WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private int policyAcceptanceCountBySource(UUID userId, String source) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM policy_acceptances WHERE user_id = ? AND source = ?")) {
      select.setObject(1, userId);
      select.setString(2, source);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private boolean sessionHashMatches(String token, UUID userId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT id_hash FROM sessions WHERE user_id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        byte[] expectedHash = sessionTokenHash(token);
        byte[] rawToken = token.getBytes(StandardCharsets.US_ASCII);
        boolean hashMatches = false;
        while (row.next()) {
          byte[] storedHash = row.getBytes(1);
          hashMatches |= MessageDigest.isEqual(expectedHash, storedHash);
          assertFalse(
              MessageDigest.isEqual(rawToken, storedHash),
              "the database must never store the raw cookie token");
        }
        return hashMatches;
      }
    }
  }

  private boolean confirmationHashStoredForUser(String token, UUID userId, String sessionToken)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT token_hash, user_id, session_id_hash
                     FROM session_revoke_confirmations
                     WHERE token_hash = ?
                     """)) {
      byte[] expectedConfirmationHash = sessionTokenHash(token);
      byte[] expectedSessionHash = sessionTokenHash(sessionToken);
      select.setBytes(1, expectedConfirmationHash);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return false;
        }
        assertEquals(userId, row.getObject("user_id", UUID.class));
        assertTrue(MessageDigest.isEqual(expectedConfirmationHash, row.getBytes("token_hash")));
        assertTrue(MessageDigest.isEqual(expectedSessionHash, row.getBytes("session_id_hash")));
        assertFalse(
            MessageDigest.isEqual(
                token.getBytes(StandardCharsets.US_ASCII), row.getBytes("token_hash")),
            "the database must never store the raw confirmation token");
        return true;
      }
    }
  }

  private boolean confirmationHashExists(String token) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM session_revoke_confirmations WHERE token_hash = ?")) {
      select.setBytes(1, sessionTokenHash(token));
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1) == 1;
      }
    }
  }

  private static byte[] sessionTokenHash(String token) throws Exception {
    byte[] secret = Base64.getUrlDecoder().decode(token);
    return MessageDigest.getInstance("SHA-256").digest(secret);
  }

  private Instant storedSessionLastSeen(byte[] tokenHash) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT last_seen_at FROM sessions WHERE id_hash = ?")) {
      select.setBytes(1, tokenHash);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
      }
    }
  }

  private int activeSessionCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private void deleteSessionTestUser(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteConfirmations =
          connection.prepareStatement(
              "DELETE FROM session_revoke_confirmations WHERE user_id = ?")) {
        deleteConfirmations.setObject(1, userId);
        deleteConfirmations.executeUpdate();
      }
      try (PreparedStatement deletePolicyAcceptances =
          connection.prepareStatement("DELETE FROM policy_acceptances WHERE user_id = ?")) {
        deletePolicyAcceptances.setObject(1, userId);
        deletePolicyAcceptances.executeUpdate();
      }
      try (PreparedStatement deleteRightsAcceptances =
          connection.prepareStatement("DELETE FROM import_rights_acceptances WHERE user_id = ?")) {
        deleteRightsAcceptances.setObject(1, userId);
        deleteRightsAcceptances.executeUpdate();
      }
      try (PreparedStatement deleteIdentities =
          connection.prepareStatement("DELETE FROM external_identities WHERE user_id = ?")) {
        deleteIdentities.setObject(1, userId);
        deleteIdentities.executeUpdate();
      }
      try (PreparedStatement deleteSessions =
          connection.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
        deleteSessions.setObject(1, userId);
        deleteSessions.executeUpdate();
      }
      try (PreparedStatement deleteUser =
          connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
        deleteUser.setObject(1, userId);
        deleteUser.executeUpdate();
      }
    }
  }

  private ReaderHistoryFixture createReaderHistoryFixture(UUID ownerId, String title, String body)
      throws Exception {
    LibraryRepository.LibraryRecord library =
        libraryService.createLibrary(ownerId, title + " Shelf");
    LibraryRepository.WorkRecord work =
        libraryService.createPrivateImportedWork(
            ownerId,
            library.id(),
            title,
            "History Author",
            "vi",
            sha256("reader-history-" + title));
    LibraryRepository.VolumeRecord volume =
        libraryService.createVolume(ownerId, work.id(), 1, "Tập 1");
    LibraryRepository.ChapterRecord chapter =
        libraryService.createChapter(ownerId, work.id(), volume.id(), 1, title + " Chapter", "vi");
    libraryService.appendChapterRevision(
        ownerId,
        chapter.id(),
        "plain_text",
        body,
        List.of(new LibraryService.NewSegment(1, "paragraph", body, "history-anchor")));
    return new ReaderHistoryFixture(work.id(), chapter.id());
  }

  private void setReaderProgressTimestamp(UUID userId, UUID workId, Instant timestamp)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE reader_progress SET updated_at = ? WHERE user_id = ? AND work_id = ?")) {
      update.setObject(1, OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
      update.setObject(2, userId);
      update.setObject(3, workId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void deleteReaderHistoryWork(UUID ownerId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "UPDATE works SET status = 'deleted', deleted_at = clock_timestamp() "
                    + "WHERE owner_user_id = ? AND id = ?")) {
      delete.setObject(1, ownerId);
      delete.setObject(2, workId);
      assertEquals(1, delete.executeUpdate());
    }
  }

  private int readerProgressCount(UUID userId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM reader_progress WHERE user_id = ? AND work_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private void deleteReaderHistoryFixtures(UUID userId, List<ReaderHistoryFixture> fixtures)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteProgress =
          connection.prepareStatement("DELETE FROM reader_progress WHERE user_id = ?")) {
        deleteProgress.setObject(1, userId);
        deleteProgress.executeUpdate();
      }
      try (PreparedStatement deleteAnnotations =
          connection.prepareStatement("DELETE FROM reader_annotations WHERE user_id = ?")) {
        deleteAnnotations.setObject(1, userId);
        deleteAnnotations.executeUpdate();
      }
      for (ReaderHistoryFixture fixture : fixtures) {
        UUID workId = fixture.workId();
        try (PreparedStatement deleteBookmarkRetries =
            connection.prepareStatement(
                "DELETE FROM reader_bookmark_idempotency WHERE response_work_id = ? OR "
                    + "response_chapter_id IN (SELECT id FROM chapters WHERE work_id = ?)")) {
          deleteBookmarkRetries.setObject(1, workId);
          deleteBookmarkRetries.setObject(2, workId);
          deleteBookmarkRetries.executeUpdate();
        }
        try (PreparedStatement deleteBookmarks =
            connection.prepareStatement("DELETE FROM reader_bookmarks WHERE work_id = ?")) {
          deleteBookmarks.setObject(1, workId);
          deleteBookmarks.executeUpdate();
        }
        try (PreparedStatement deleteSegments =
            connection.prepareStatement(
                "DELETE FROM segments s USING chapter_revisions r, chapters c "
                    + "WHERE s.chapter_revision_id = r.id AND r.chapter_id = c.id AND c.work_id = ?")) {
          deleteSegments.setObject(1, workId);
          deleteSegments.executeUpdate();
        }
        try (PreparedStatement detachRevisions =
            connection.prepareStatement(
                "UPDATE chapters SET source_revision_id = NULL WHERE work_id = ?")) {
          detachRevisions.setObject(1, workId);
          detachRevisions.executeUpdate();
        }
        try (PreparedStatement deleteRevisions =
            connection.prepareStatement(
                "DELETE FROM chapter_revisions r USING chapters c "
                    + "WHERE r.chapter_id = c.id AND c.work_id = ?")) {
          deleteRevisions.setObject(1, workId);
          deleteRevisions.executeUpdate();
        }
        for (String table : List.of("chapters", "volumes")) {
          try (PreparedStatement deleteChildren =
              connection.prepareStatement("DELETE FROM " + table + " WHERE work_id = ?")) {
            deleteChildren.setObject(1, workId);
            deleteChildren.executeUpdate();
          }
        }
        try (PreparedStatement deleteWork =
            connection.prepareStatement("DELETE FROM works WHERE id = ? AND owner_user_id = ?")) {
          deleteWork.setObject(1, workId);
          deleteWork.setObject(2, userId);
          deleteWork.executeUpdate();
        }
      }
      try (PreparedStatement deleteLibraries =
          connection.prepareStatement("DELETE FROM libraries WHERE owner_user_id = ?")) {
        deleteLibraries.setObject(1, userId);
        deleteLibraries.executeUpdate();
      }
    }
  }

  private record ReaderHistoryFixture(UUID workId, UUID chapterId) {}

  private void deleteBareAuthoredWorkData(UUID ownerId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String table :
          List.of("authoring_chapter_creations", "authoring_manuscript_creations")) {
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM " + table + " WHERE work_id = ?")) {
          delete.setObject(1, workId);
          delete.executeUpdate();
        }
      }
      for (String table : List.of("chapters", "volumes")) {
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM " + table + " WHERE work_id = ?")) {
          delete.setObject(1, workId);
          delete.executeUpdate();
        }
      }
      try (PreparedStatement deleteWork =
          connection.prepareStatement("DELETE FROM works WHERE id = ? AND owner_user_id = ?")) {
        deleteWork.setObject(1, workId);
        deleteWork.setObject(2, ownerId);
        deleteWork.executeUpdate();
      }
    }
  }

  private void deleteOwnerLibraries(UUID ownerId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM libraries WHERE owner_user_id = ?")) {
      delete.setObject(1, ownerId);
      delete.executeUpdate();
    }
  }

  private void deleteLibraryUserData(UUID userId, UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteAnnotations =
          connection.prepareStatement("DELETE FROM reader_annotations WHERE user_id = ?")) {
        deleteAnnotations.setObject(1, userId);
        deleteAnnotations.executeUpdate();
      }
      try (PreparedStatement deleteProgress =
          connection.prepareStatement("DELETE FROM reader_progress WHERE user_id = ?")) {
        deleteProgress.setObject(1, userId);
        deleteProgress.executeUpdate();
      }
      try (PreparedStatement deleteAssignments =
          connection.prepareStatement(
              "DELETE FROM library_collection_works WHERE owner_user_id = ?")) {
        deleteAssignments.setObject(1, userId);
        deleteAssignments.executeUpdate();
      }
      try (PreparedStatement deleteStates =
          connection.prepareStatement("DELETE FROM library_reading_states WHERE user_id = ?")) {
        deleteStates.setObject(1, userId);
        deleteStates.executeUpdate();
      }
      try (PreparedStatement deleteCollections =
          connection.prepareStatement("DELETE FROM library_collections WHERE owner_user_id = ?")) {
        deleteCollections.setObject(1, userId);
        deleteCollections.executeUpdate();
      }
      if (workId != null) {
        try (PreparedStatement deleteDraftRevisionWrites =
            connection.prepareStatement(
                "DELETE FROM authoring_chapter_revision_writes WHERE work_id = ?")) {
          deleteDraftRevisionWrites.setObject(1, workId);
          deleteDraftRevisionWrites.executeUpdate();
        }
        try (PreparedStatement deleteChapterCreations =
            connection.prepareStatement(
                "DELETE FROM authoring_chapter_creations WHERE work_id = ?")) {
          deleteChapterCreations.setObject(1, workId);
          deleteChapterCreations.executeUpdate();
        }
        try (PreparedStatement deleteManuscriptCreations =
            connection.prepareStatement(
                "DELETE FROM authoring_manuscript_creations WHERE work_id = ?")) {
          deleteManuscriptCreations.setObject(1, workId);
          deleteManuscriptCreations.executeUpdate();
        }
        try (PreparedStatement deleteBookmarkRetries =
            connection.prepareStatement(
                "DELETE FROM reader_bookmark_idempotency WHERE response_work_id = ? OR "
                    + "response_chapter_id IN (SELECT id FROM chapters WHERE work_id = ?)")) {
          deleteBookmarkRetries.setObject(1, workId);
          deleteBookmarkRetries.setObject(2, workId);
          deleteBookmarkRetries.executeUpdate();
        }
        try (PreparedStatement deleteBookmarks =
            connection.prepareStatement("DELETE FROM reader_bookmarks WHERE work_id = ?")) {
          deleteBookmarks.setObject(1, workId);
          deleteBookmarks.executeUpdate();
        }
        try (PreparedStatement deleteSegments =
            connection.prepareStatement(
                "DELETE FROM segments s USING chapter_revisions r, chapters c "
                    + "WHERE s.chapter_revision_id = r.id AND r.chapter_id = c.id "
                    + "AND c.work_id = ?")) {
          deleteSegments.setObject(1, workId);
          deleteSegments.executeUpdate();
        }
        try (PreparedStatement detachChapterRevisions =
            connection.prepareStatement(
                "UPDATE chapters SET source_revision_id = NULL WHERE work_id = ?")) {
          detachChapterRevisions.setObject(1, workId);
          detachChapterRevisions.executeUpdate();
        }
        try (PreparedStatement deleteRevisions =
            connection.prepareStatement(
                "DELETE FROM chapter_revisions r USING chapters c "
                    + "WHERE r.chapter_id = c.id AND c.work_id = ?")) {
          deleteRevisions.setObject(1, workId);
          deleteRevisions.executeUpdate();
        }
        try (PreparedStatement deleteChapters =
            connection.prepareStatement("DELETE FROM chapters WHERE work_id = ?")) {
          deleteChapters.setObject(1, workId);
          deleteChapters.executeUpdate();
        }
        try (PreparedStatement deleteVolumes =
            connection.prepareStatement("DELETE FROM volumes WHERE work_id = ?")) {
          deleteVolumes.setObject(1, workId);
          deleteVolumes.executeUpdate();
        }
        try (PreparedStatement deleteWork =
            connection.prepareStatement("DELETE FROM works WHERE id = ? AND owner_user_id = ?")) {
          deleteWork.setObject(1, workId);
          deleteWork.setObject(2, userId);
          deleteWork.executeUpdate();
        }
      }
      try (PreparedStatement deleteLibraries =
          connection.prepareStatement("DELETE FROM libraries WHERE owner_user_id = ?")) {
        deleteLibraries.setObject(1, userId);
        deleteLibraries.executeUpdate();
      }
    }
  }

  private String storedBootstrapReturnPath(String nonce)
      throws SQLException, NoSuchAlgorithmException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT safe_return_path FROM google_auth_attempts WHERE nonce_hash = ?")) {
      select.setBytes(1, sha256(nonce));
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private int googleAuthAttemptCount(UUID correlationId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM google_auth_attempts WHERE correlation_id = ?")) {
      select.setObject(1, correlationId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private void deleteBootstrapFixtures(String clientAddress, List<UUID> correlationIds)
      throws Exception {
    byte[] keyHash = bootstrapRateLimitKeyHash(clientAddress);
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteAttempt =
          connection.prepareStatement(
              "DELETE FROM google_auth_attempts WHERE correlation_id = ?")) {
        for (UUID correlationId : correlationIds) {
          deleteAttempt.setObject(1, correlationId);
          deleteAttempt.addBatch();
        }
        deleteAttempt.executeBatch();
      }
      try (PreparedStatement deleteBucket =
          connection.prepareStatement(
              "DELETE FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
        deleteBucket.setBytes(1, keyHash);
        deleteBucket.executeUpdate();
      }
    }
  }

  private static String bootstrapTestClientAddress() {
    return "203.0.113." + (1 + Math.floorMod(UUID.randomUUID().hashCode(), 254));
  }

  private static byte[] bootstrapRateLimitKeyHash(String clientAddress) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            Base64.getUrlDecoder().decode(BOOTSTRAP_RATE_LIMIT_HMAC_KEY), "HmacSHA256"));
    return mac.doFinal(InetAddress.getByName(clientAddress).getAddress());
  }

  private static GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity(
      String subject, String email, String name) {
    return GoogleIdentityTestFixtures.verifiedIdentity(subject, email, name);
  }

  private static Jwt googleJwt(String nonce, String subject, String email) {
    Instant now = Instant.now();
    return Jwt.withTokenValue("synthetic-google-id-token")
        .header("alg", "RS256")
        .claim("iss", "https://accounts.google.com")
        .claim("aud", List.of("novel-web-integration-test.apps.googleusercontent.com"))
        .claim("sub", subject)
        .claim("email", email)
        .claim("email_verified", true)
        .claim("name", "Integration Reader")
        .claim("nonce", nonce)
        .issuedAt(now.minusSeconds(10))
        .expiresAt(now.plusSeconds(300))
        .build();
  }

  private static byte[] sha256(String value) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] value) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private void deleteAuthAttempt(byte[] nonceHash) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM google_auth_attempts WHERE nonce_hash = ?")) {
      delete.setBytes(1, nonceHash);
      delete.executeUpdate();
    }
  }

  private void deleteRateLimitBucket(byte[] keyHash) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
      delete.setBytes(1, keyHash);
      delete.executeUpdate();
    }
  }

  private int rateLimitBucketCount(byte[] keyHash) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
      select.setBytes(1, keyHash);
      try (ResultSet result = select.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private int identityCount(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM external_identities WHERE issuer = 'https://accounts.google.com' AND subject = ?")) {
      select.setString(1, subject);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private UUID userIdForGoogleSubject(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT user_id FROM external_identities
                     WHERE issuer = 'https://accounts.google.com' AND subject = ?
                     """)) {
      select.setString(1, subject);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getObject(1, UUID.class);
      }
    }
  }

  private void deleteGoogleSignInFixtures(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT user_id FROM external_identities
                     WHERE issuer = 'https://accounts.google.com' AND subject = ?
                     """)) {
      select.setString(1, subject);
      UUID userId = null;
      try (ResultSet row = select.executeQuery()) {
        if (row.next()) {
          userId = row.getObject(1, UUID.class);
        }
      }
      if (userId == null) {
        return;
      }
      for (String table :
          List.of("sessions", "policy_acceptances", "auth_events", "external_identities")) {
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
          delete.setObject(1, userId);
          delete.executeUpdate();
        }
      }
      try (PreparedStatement deleteUser =
          connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
        deleteUser.setObject(1, userId);
        deleteUser.executeUpdate();
      }
    }
  }

  private int userCount(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT COUNT(*) FROM users WHERE id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private int userCountByEmail(String email) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT COUNT(*) FROM users WHERE contact_email = ?")) {
      select.setString(1, email);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private String identityIssuer(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT issuer FROM external_identities WHERE subject = ?")) {
      select.setString(1, subject);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String storedProviderEmail(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT provider_email FROM external_identities WHERE subject = ?")) {
      select.setString(1, subject);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private boolean providerIdentityLastSeen(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT last_seen_at IS NOT NULL FROM external_identities WHERE subject = ?")) {
      select.setString(1, subject);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean(1);
      }
    }
  }

  private String storedUserEmail(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT contact_email FROM users WHERE id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String storedUserStatus(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT status FROM users WHERE id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String storedUserDisplayName(UUID userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT display_name FROM users WHERE id = ?")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private void deleteMappedIdentity(UUID userId, String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteIdentity =
          connection.prepareStatement(
              "DELETE FROM external_identities WHERE user_id = ? AND subject = ?")) {
        deleteIdentity.setObject(1, userId);
        deleteIdentity.setString(2, subject);
        deleteIdentity.executeUpdate();
      }
      try (PreparedStatement deleteUser =
          connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
        deleteUser.setObject(1, userId);
        deleteUser.executeUpdate();
      }
    }
  }

  private void deleteMappedIdentityBySubject(String subject) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      UUID userId = null;
      try (PreparedStatement deleteIdentity =
          connection.prepareStatement(
              """
                    DELETE FROM external_identities
                    WHERE issuer = 'https://accounts.google.com' AND subject = ?
                    RETURNING user_id
                    """)) {
        deleteIdentity.setString(1, subject);
        try (ResultSet row = deleteIdentity.executeQuery()) {
          if (row.next()) {
            userId = row.getObject(1, UUID.class);
          }
        }
      }
      if (userId != null) {
        try (PreparedStatement deleteUser =
            connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
          deleteUser.setObject(1, userId);
          deleteUser.executeUpdate();
        }
      }
    }
  }

  private void deleteUserByEmail(String email) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM users WHERE contact_email = ?")) {
      delete.setString(1, email);
      delete.executeUpdate();
    }
  }

  private static void insertGoogleIdentity(
      Connection connection, UUID userId, String subject, String email) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO external_identities (
                    id, user_id, provider, issuer, subject, provider_email, provider_email_verified, last_seen_at)
                VALUES (?, ?, 'google', 'https://accounts.google.com', ?, ?, TRUE, clock_timestamp())
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setString(3, subject);
      insert.setString(4, email);
      insert.executeUpdate();
    }
  }

  private static void insertUser(
      Connection connection, String displayName, String email, String status, boolean deleted)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO users (id, display_name, contact_email, email_verified_at, status, deleted_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setString(2, displayName);
      insert.setString(3, email);
      insert.setString(4, status);
      if (deleted) {
        insert.setTimestamp(5, Timestamp.from(Instant.now()));
      } else {
        insert.setTimestamp(5, null);
      }
      insert.executeUpdate();
    }
  }

  private record SyntheticPublicationScopeFixture(UUID reviewerId, String policyVersion) {}
}
