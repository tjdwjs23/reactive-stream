package demo.board.adapter.out.persistence

import demo.board.application.port.out.TransactionRunnerPort
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

// TransactionRunnerPort의 Spring R2DBC 구현. 리액티브 트랜잭션 경계를 코루틴 경계로 이어줍니다.
//
// Boot가 R2DBC 스택에서 자동 구성하는 ReactiveTransactionManager로 TransactionalOperator를 만들고,
// executeAndAwait로 suspend 블록을 하나의 트랜잭션 안에서 실행합니다(블록 내 R2DBC 접근은 같은 커넥션을 공유).
// 트랜잭션 기술이 이 어댑터에만 갇혀, 애플리케이션 서비스는 프레임워크 없이 원자성만 요청합니다.
@Component
class SpringTransactionRunner(
    transactionManager: ReactiveTransactionManager,
) : TransactionRunnerPort {
    private val transactionalOperator = TransactionalOperator.create(transactionManager)

    override suspend fun <T> execute(block: suspend () -> T): T = transactionalOperator.executeAndAwait { block() }
}
