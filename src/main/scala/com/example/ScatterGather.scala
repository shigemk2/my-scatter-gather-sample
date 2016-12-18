package com.example

import akka.actor._

case class RequestForQuotation(rfqId: String, retailItems: Seq[RetailItem]) {
  val totalRetailPrice: Double = retailItems.map(retailItem => retailItem.retailPrice).sum
}
case class RetailItem(itemId: String, retailPrice: Double)
case class RequestPriceQuote(rfqId: String, itemId: String, retailPrice: Double, orderTotalRetailPrice: Double)
case class PriceQuote(quoterId: String, rfqId: String, itemId: String, retailPrice: Double, discountPrice: Double)
case class PriceQuoteFulfilled(priceQuote: PriceQuote)
case class PriceQuoteTimedOut(rfqId: String)
case class RequiredPriceQuotesForFulfillment(rfqId: String, quotesRequested: Int)
case class QuotationFulfillment(rfqId: String, quotesRequested: Int, priceQuotes: Seq[PriceQuote], requester: ActorRef)
case class BestPriceQuotation(rfqId: String, priceQuotes: Seq[PriceQuote])
case class SubscribeToPriceQuoteRequests(quoterId: String, quoteProcessor: ActorRef)

object ScatterGatherDriver extends CompletableApp(5) {
}

class MountaineeringSuppliesOrderProcessor(priceQuoteAggregator: ActorRef) extends Actor {
  val subscribers = scala.collection.mutable.Map[String, SubscribeToPriceQuoteRequests]()

  def dispatch(rfq: RequestForQuotation) = {
    subscribers.values.foreach { subscriber =>
      val quoteProcessor = subscriber.quoteProcessor
      rfq.retailItems.foreach { retailItem =>
        println("OrderProcessor: " + rfq.rfqId + " item: " + retailItem.itemId + " to: " + subscriber.quoterId)
        quoteProcessor ! RequestPriceQuote(rfq.rfqId, retailItem.itemId, retailItem.retailPrice, rfq.totalRetailPrice)
      }
    }
  }

  def receive = {
    case subscriber: SubscribeToPriceQuoteRequests =>
      subscribers(subscriber.quoterId) = subscriber
    case priceQuote: PriceQuote =>
      priceQuoteAggregator ! PriceQuoteFulfilled(priceQuote)
      println(s"OrderProcessor: received: $priceQuote")
    case rfq: RequestForQuotation =>
      priceQuoteAggregator ! RequiredPriceQuotesForFulfillment(rfq.rfqId, subscribers.size * rfq.retailItems.size)
      dispatch(rfq)
    case bestPriceQuotation: BestPriceQuotation =>
      println(s"OrderProcessor: received: $bestPriceQuotation")
      ScatterGatherDriver.completedStep()
    case message: Any =>
      println(s"OrderProcessor: received unexpected message: $message")
  }
}

class PriceQuoteAggregator extends Actor {
  val fulfilledPriceQuotes = scala.collection.mutable.Map[String, QuotationFulfillment]()

  def bestPriceQuotationFrom(quotationFulfillment: QuotationFulfillment): BestPriceQuotation = {
    val bestPrices = scala.collection.mutable.Map[String, PriceQuote]()

    quotationFulfillment.priceQuotes.foreach { priceQuote =>
      if (bestPrices.contains(priceQuote.itemId)) {
        if (bestPrices(priceQuote.itemId).discountPrice > priceQuote.discountPrice) {
          bestPrices(priceQuote.itemId) = priceQuote
        }
      } else {
        bestPrices(priceQuote.itemId) = priceQuote
      }
    }

    BestPriceQuotation(quotationFulfillment.rfqId, bestPrices.values.toVector)
  }

  def receive = {
    case required: RequiredPriceQuotesForFulfillment =>
      fulfilledPriceQuotes(required.rfqId) = QuotationFulfillment(required.rfqId, required.quotesRequested, Vector(), sender)
      val duration = Duration.create(2, TimeUnit.SECONDS)
      context.system.scheduler.scheduleOnce(duration, self, PriceQuoteTimedOut(required.rfqId))
    case priceQuoteFulfilled: PriceQuoteFulfilled =>
      priceQuoteRequestFulfilled(priceQuoteFulfilled)
      println(s"PriceQuoteAggregator: fulfilled price quote: $PriceQuoteFulfilled")
    case priceQuoteTimedOut: PriceQuoteTimedOut =>
      priceQuoteRequestTimedOut(priceQuoteTimedOut.rfqId)
    case message: Any =>
      println(s"PriceQuoteAggregator: received unexpected message: $message")
  }

  def priceQuoteRequestFulfilled(priceQuoteFulfilled: PriceQuoteFulfilled) = {
    if (fulfilledPriceQuotes.contains(priceQuoteFulfilled.priceQuote.rfqId)) {
      val previousFulfillment = fulfilledPriceQuotes(priceQuoteFulfilled.priceQuote.rfqId)
      val currentPriceQuotes = previousFulfillment.priceQuotes :+ priceQuoteFulfilled.priceQuote
      val currentFulfillment =
        QuotationFulfillment(
          previousFulfillment.rfqId,
          previousFulfillment.quotesRequested,
          currentPriceQuotes,
          previousFulfillment.requester)

      if (currentPriceQuotes.size >= currentFulfillment.quotesRequested) {
        quoteBestPrice(currentFulfillment)
      } else {
        fulfilledPriceQuotes(priceQuoteFulfilled.priceQuote.rfqId) = currentFulfillment
      }
    }
  }

  def priceQuoteRequestTimedOut(rfqId: String) = {
    if (fulfilledPriceQuotes.contains(rfqId)) {
      quoteBestPrice(fulfilledPriceQuotes(rfqId))
    }
  }

  def quoteBestPrice(quotationFulfillment: QuotationFulfillment) = {
    if (fulfilledPriceQuotes.contains(quotationFulfillment.rfqId)) {
      quotationFulfillment.requester ! bestPriceQuotationFrom(quotationFulfillment)
      fulfilledPriceQuotes.remove(quotationFulfillment.rfqId)
    }
  }
}

class BudgetHikersPriceQuotes(priceQuoteRequestPublisher: ActorRef) extends Actor {
  val quoterId = self.path.name
  priceQuoteRequestPublisher ! SubscribeToPriceQuoteRequests(quoterId, self)

  def receive = {
    case rpq: RequestPriceQuote =>
      if (rpq.orderTotalRetailPrice < 1000.00) {
        val discount = discountPercentage(rpq.orderTotalRetailPrice) * rpq.retailPrice
        sender ! PriceQuote(quoterId, rpq.rfqId, rpq.itemId, rpq.retailPrice, rpq.retailPrice - discount)
      } else {
        println(s"BudgetHikersPriceQuotes: ignoring: $rpq")
      }

    case message: Any =>
      println(s"BudgetHikersPriceQuotes: received unexpected message: $message")
  }

  def discountPercentage(orderTotalRetailPrice: Double) = {
    if (orderTotalRetailPrice <= 100.00) 0.02
    else if (orderTotalRetailPrice <= 399.99) 0.03
    else if (orderTotalRetailPrice <= 499.99) 0.05
    else if (orderTotalRetailPrice <= 799.99) 0.07
    else 0.075
  }
}

class HighSierraPriceQuotes(priceQuoteRequestPublisher: ActorRef) extends Actor {
  val quoterId = self.path.name
  priceQuoteRequestPublisher ! SubscribeToPriceQuoteRequests(quoterId, self)

  def receive = {
    case rpq: RequestPriceQuote =>
      val discount = discountPercentage(rpq.orderTotalRetailPrice) * rpq.retailPrice
      sender ! PriceQuote(quoterId, rpq.rfqId, rpq.itemId, rpq.retailPrice, rpq.retailPrice - discount)

    case message: Any =>
      println(s"HighSierraPriceQuotes: received unexpected message: $message")
  }

  def discountPercentage(orderTotalRetailPrice: Double): Double = {
    if (orderTotalRetailPrice <= 150.00) 0.015
    else if (orderTotalRetailPrice <= 499.99) 0.02
    else if (orderTotalRetailPrice <= 999.99) 0.03
    else if (orderTotalRetailPrice <= 4999.99) 0.04
    else 0.05
  }
}

class MountainAscentPriceQuotes(priceQuoteRequestPublisher: ActorRef) extends Actor {
  val quoterId = self.path.name
  priceQuoteRequestPublisher ! SubscribeToPriceQuoteRequests(quoterId, self)

  def receive = {
    case rpq: RequestPriceQuote =>
      val discount = discountPercentage(rpq.orderTotalRetailPrice) * rpq.retailPrice
      sender ! PriceQuote(quoterId, rpq.rfqId, rpq.itemId, rpq.retailPrice, rpq.retailPrice - discount)

    case message: Any =>
      println(s"MountainAscentPriceQuotes: received unexpected message: $message")
  }

  def discountPercentage(orderTotalRetailPrice: Double) = {
    if (orderTotalRetailPrice <= 99.99) 0.01
    else if (orderTotalRetailPrice <= 199.99) 0.02
    else if (orderTotalRetailPrice <= 499.99) 0.03
    else if (orderTotalRetailPrice <= 799.99) 0.04
    else if (orderTotalRetailPrice <= 999.99) 0.045
    else if (orderTotalRetailPrice <= 2999.99) 0.0475
    else 0.05
  }
}

class PinnacleGearPriceQuotes(priceQuoteRequestPublisher: ActorRef) extends Actor {
  val quoterId = self.path.name
  priceQuoteRequestPublisher ! SubscribeToPriceQuoteRequests(quoterId, self)

  def receive = {
    case rpq: RequestPriceQuote =>
      val discount = discountPercentage(rpq.orderTotalRetailPrice) * rpq.retailPrice
      sender ! PriceQuote(quoterId, rpq.rfqId, rpq.itemId, rpq.retailPrice, rpq.retailPrice - discount)

    case message: Any =>
      println(s"PinnacleGearPriceQuotes: received unexpected message: $message")
  }

  def discountPercentage(orderTotalRetailPrice: Double) = {
    if (orderTotalRetailPrice <= 299.99) 0.015
    else if (orderTotalRetailPrice <= 399.99) 0.0175
    else if (orderTotalRetailPrice <= 499.99) 0.02
    else if (orderTotalRetailPrice <= 999.99) 0.03
    else if (orderTotalRetailPrice <= 1199.99) 0.035
    else if (orderTotalRetailPrice <= 4999.99) 0.04
    else if (orderTotalRetailPrice <= 7999.99) 0.05
    else 0.06
  }
}

class RockBottomOuterwearPriceQuotes(priceQuoteRequestPublisher: ActorRef) extends Actor {
  val quoterId = self.path.name
  priceQuoteRequestPublisher ! SubscribeToPriceQuoteRequests(quoterId, self)

  def receive = {
    case rpq: RequestPriceQuote =>
      if (rpq.orderTotalRetailPrice < 2000.00) {
        val discount = discountPercentage(rpq.orderTotalRetailPrice) * rpq.retailPrice
        sender ! PriceQuote(quoterId, rpq.rfqId, rpq.itemId, rpq.retailPrice, rpq.retailPrice - discount)
      } else {
        println(s"RockBottomOuterwearPriceQuotes: ignoring: $rpq")
      }

    case message: Any =>
      println(s"RockBottomOuterwearPriceQuotes: received unexpected message: $message")
  }

  def discountPercentage(orderTotalRetailPrice: Double) = {
    if (orderTotalRetailPrice <= 100.00) 0.015
    else if (orderTotalRetailPrice <= 399.99) 0.02
    else if (orderTotalRetailPrice <= 499.99) 0.03
    else if (orderTotalRetailPrice <= 799.99) 0.04
    else if (orderTotalRetailPrice <= 999.99) 0.05
    else if (orderTotalRetailPrice <= 2999.99) 0.06
    else if (orderTotalRetailPrice <= 4999.99) 0.07
    else if (orderTotalRetailPrice <= 5999.99) 0.075
    else 0.08
  }
}