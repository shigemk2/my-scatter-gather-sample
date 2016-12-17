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
