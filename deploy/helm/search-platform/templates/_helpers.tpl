{{/*
관측성 env 스위치. observability.enabled=true면 OTLP endpoint를, false면 export 비활성화 env를 방출합니다.
search-service / search-indexer 두 Deployment가 공유합니다.
사용: {{- include "search-platform.observabilityEnv" . | nindent 12 }}
*/}}
{{- define "search-platform.observabilityEnv" -}}
{{- if .Values.observability.enabled }}
- name: OTLP_TRACING_ENDPOINT
  value: "{{ .Values.observability.otlpEndpointBase }}/v1/traces"
- name: OTLP_LOGS_ENDPOINT
  value: "{{ .Values.observability.otlpEndpointBase }}/v1/logs"
- name: OTLP_METRICS_URL
  value: "{{ .Values.observability.otlpEndpointBase }}/v1/metrics"
{{- else }}
- name: MANAGEMENT_TRACING_ENABLED
  value: "false"
- name: MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED
  value: "false"
- name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_ENABLED
  value: "false"
{{- end }}
{{- end -}}
