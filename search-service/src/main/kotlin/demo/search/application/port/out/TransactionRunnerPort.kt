package demo.search.application.port.out

// "여러 DB 작업을 하나의 트랜잭션으로 묶어 실행"을 추상화하는 out-port.
//
// 서비스는 대부분 단일 문장 오토커밋으로 처리하지만(트랜잭션은 DB 이득이 있을 때만), Transactional Outbox는
// 게시글 쓰기 + 아웃박스 기록을 반드시 원자적으로 묶어야 유실이 없습니다. 이 딱 한 경계에만 트랜잭션을 씁니다.
//
// Spring의 PlatformTransactionManager/TransactionTemplate 같은 구체 기술은 어댑터가 감춥니다 —
// 서비스는 "이 블록을 원자적으로 실행해줘"만 압니다. 덕분에 서비스는 트랜잭션 프레임워크 없이 단위테스트됩니다.
interface TransactionRunnerPort {
    fun <T> execute(block: () -> T): T
}
