package org.tianea.secretary.telegram

import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.objects.message.Message
import java.util.concurrent.ConcurrentLinkedQueue

@Service
class TelegramChatMessageService {
    private val queue: ConcurrentLinkedQueue<Message> = ConcurrentLinkedQueue()

    fun addMessage(message: Message) {
        queue.offer(message)
    }

    fun popMessage(): Message {
        if (queue.isEmpty()) throw IllegalStateException("No messages populated")

        return queue.poll()
    }

    fun isEmpty(): Boolean = queue.isEmpty()
}
