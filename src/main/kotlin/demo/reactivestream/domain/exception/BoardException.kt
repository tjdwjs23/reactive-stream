package demo.reactivestream.domain.exception

class BoardNotFoundException(
    id: Long,
) : RuntimeException("Board not found with id: $id")

class BoardValidationException(
    message: String,
) : RuntimeException(message)
