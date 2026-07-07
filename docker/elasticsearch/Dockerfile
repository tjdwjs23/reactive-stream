# Elasticsearch 9.2.2 + 한글 형태소 분석기(Nori) 플러그인
#
# 버전 주의: Spring Boot 4가 번들하는 Elasticsearch 자바 클라이언트(9.2.x)와 major/minor를
# 맞춰야 합니다. ES 8 서버는 ES 9 클라이언트와 프로토콜(호환 미디어 타입)이 맞지 않아 통신이
# 깨지므로 사용하지 않습니다.
#
# 공식 ES 이미지에는 analysis-nori가 들어있지 않으므로, 여기서 미리 설치한 커스텀 이미지를 굽습니다.
# 로컬 k8s는 deploy/build-and-load.sh가 이 Dockerfile을 빌드해 kind로 주입하고, 테스트는 Testcontainers가 빌드합니다.
# --batch: 설치 중 대화형 프롬프트 없이 진행(비대화형 빌드용).
FROM docker.elastic.co/elasticsearch/elasticsearch:9.2.2

RUN bin/elasticsearch-plugin install --batch analysis-nori
