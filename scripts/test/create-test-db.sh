#!/usr/bin/env bash

# 테스트 전용 DB(coupon_db_test) 생성 및 스키마 적용.
#
#
# docs/schema.sql 은 건드리지 않는다.
#   그 파일이 CREATE DATABASE / USE 로 coupon_db 를 직접 지정하므로,
#   DB 이름만 치환해서 파이프로 넘긴다. schema.sql 안에 coupon_db 문자열은
#   CREATE DATABASE / USE / 확인 쿼리 2곳 총 4줄뿐이고 전부 치환 대상이다.
#   덕분에 팀의 기존 로드 명령과 scripts/test/reset-schema.sh 는 그대로 쓴다.
#
# 최초 1회만 실행하면 된다. 이후 schema.sql 이 바뀌면 다시 실행한다.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${PROJECT_ROOT}"

SOURCE_DB="coupon_db"
TEST_DB="${TEST_DB:-coupon_db_test}"
APP_USER="${APP_USER:-coupon}"

if [[ "${TEST_DB}" == "${SOURCE_DB}" ]]; then
    echo "TEST_DB must not be ${SOURCE_DB}. That is the development database."
    exit 1
fi

echo "WARNING: This will DROP and recreate every table in ${TEST_DB}."
echo "${SOURCE_DB} is not touched."
read -r -p "Type CREATE to continue: " confirmation

if [[ "${confirmation}" != "CREATE" ]]; then
    echo "Cancelled. No data was changed."
    exit 1
fi

docker compose up -d mysql

mysql_ready=false
for _ in {1..30}; do
    if docker exec coupon-mysql mysqladmin ping -uroot -proot1234 --silent >/dev/null 2>&1; then
        mysql_ready=true
        break
    fi

    echo "Waiting for MySQL..."
    sleep 2
done

if [[ "${mysql_ready}" != "true" ]]; then
    echo "MySQL did not become ready within 60 seconds."
    exit 1
fi

# DB 이름만 치환해서 그대로 흘려보낸다. schema.sql 이 CREATE DATABASE 까지
# 들고 있으므로 DB 생성도 여기서 함께 처리된다.
sed "s/${SOURCE_DB}/${TEST_DB}/g" docs/schema.sql \
    | docker exec -i coupon-mysql mysql -uroot -proot1234 --default-character-set=utf8mb4 \
    > /dev/null

# 도커 이미지는 MYSQL_DATABASE(coupon_db)에만 권한을 부여한다.
# 테스트 DB 에는 GRANT 를 따로 해야 애플리케이션 계정이 접속할 수 있다.
docker exec -i coupon-mysql mysql -uroot -proot1234 <<SQL
GRANT ALL PRIVILEGES ON ${TEST_DB}.* TO '${APP_USER}'@'%';
FLUSH PRIVILEGES;
SQL

echo
echo "Verifying ${TEST_DB} ..."
docker exec coupon-mysql mysql -uroot -proot1234 -N -e "
SELECT CONCAT('  tables = ', COUNT(*))
FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${TEST_DB}';
SELECT CONCAT('  checks = ', COUNT(*))
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = '${TEST_DB}' AND CONSTRAINT_TYPE = 'CHECK';"

echo
echo "Expected: tables = 7, checks = 10"
echo "(checks = 0 이면 MySQL 8.0.16 미만이라 CHECK 제약이 무시된 것이다)"
echo
echo "Test database ready."
echo "./gradlew test uses DB_NAME=${TEST_DB} (see build.gradle)."