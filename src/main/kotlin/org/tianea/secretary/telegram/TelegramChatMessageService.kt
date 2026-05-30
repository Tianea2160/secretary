package org.tianea.secretary.telegram

import java.util.concurrent.ConcurrentLinkedQueue
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.objects.message.Message

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
