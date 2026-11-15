package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicCatalogControllerTests {

  @Test
  void disabledCatalogFailsClosedWithoutQueryingTheRepository() {
    PublicCatalogService service = mock(PublicCatalogService.class);
    PublicCatalogController controller = new PublicCatalogController(service, false);
    HttpServletRequest request = new MockHttpServletRequest();

    var response = controller.listWorks(null, null, null, "20", request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verifyNoInteractions(service);
  }
}
