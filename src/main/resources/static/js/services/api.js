/* =====================================================================
   Control Tower API client
   Every call shares the same base path and handles network failures
   uniformly: a single failed poll must never break the dashboard.
   ===================================================================== */

const API = (() => {
  const BASE = '/api/v1';

  /** API error carrying the HTTP status, so callers can tailor the message. */
  class ApiError extends Error {
    constructor(message, status) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
    }
  }

  /**
   * GET with a timeout. The AbortController stops a slow response from
   * overlapping with the next refresh.
   */
  async function get(path, { timeoutMs = 8000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(`${BASE}${path}`, {
        method: 'GET',
        headers: { 'Accept': 'application/json' },
        signal: controller.signal,
        cache: 'no-store',
      });

      if (!response.ok) {
        throw new ApiError(`GET ${path} returned ${response.status}`, response.status);
      }
      return await response.json();
    } catch (error) {
      if (error.name === 'AbortError') {
        throw new ApiError(`Request to ${path} timed out`, 0);
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  /** POST with a JSON body. */
  async function post(path, body, { timeoutMs = 8000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(`${BASE}${path}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (response.status === 400) {
        // Bean Validation returns ProblemDetail with the field errors.
        const detail = await response.json().catch(() => null);
        throw new ApiError(extractValidationMessage(detail), 400);
      }
      if (!response.ok) {
        throw new ApiError(`POST ${path} returned ${response.status}`, response.status);
      }
      return await response.json();
    } catch (error) {
      if (error.name === 'AbortError') {
        throw new ApiError(`Request to ${path} timed out`, 0);
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  function extractValidationMessage(detail) {
    const errors = detail && detail.errors;
    if (errors && Object.keys(errors).length > 0) {
      return Object.entries(errors)
        .map(([field, messages]) => `${field}: ${[].concat(messages).join(', ')}`)
        .join(' | ');
    }
    return (detail && detail.detail) || 'Invalid parameters.';
  }

  return {
    ApiError,
    kpis: () => get('/dashboard/kpis'),
    abcCurve: () => get('/supplies/abc-curve'),
    turnover: () => get('/supplies/turnover'),
    supplierPerformance: () => get('/supplies/supplier-performance'),
    workOrderFulfilment: () => get('/work-orders/fulfilment'),
    stockoutAlerts: (severity) =>
      get(severity ? `/supplies/stockout-alerts?severity=${encodeURIComponent(severity)}`
                   : '/supplies/stockout-alerts'),
    supplyItems: () => get('/supplies'),
    simulationParameters: () => get('/simulation/parameters'),
    updateSimulationParameters: (body) => post('/simulation/parameters', body),
  };
})();