package com.typesafe.webwords.common

import scala.collection.JavaConverters._
import java.net.URL
import com.mongodb.casbah.MongoConnection
import com.mongodb.casbah.MongoDB
import com.mongodb.casbah.MongoCollection
import com.mongodb.casbah.MongoDBAddress
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBList
import java.util.concurrent.TimeUnit
import org.bson.types.BasicBSONList
import com.mongodb.casbah.MongoURI
import com.mongodb.casbah.WriteConcern
import akka.actor.Actor
import akka.actor.Props
import akka.routing.RoundRobinRouter

sealed trait IndexStorageRequest
case class CacheIndex(url: String, index: Index) extends IndexStorageRequest
case class FetchCachedIndex(url: String) extends IndexStorageRequest
case object DropCache extends IndexStorageRequest
case object GetCacheSize extends IndexStorageRequest

sealed trait IndexStorageReply
case class IndexCached(url: String) extends IndexStorageReply
case class CachedIndexFetched(index: Option[Index]) extends IndexStorageReply
case class CacheSize(size: Long) extends IndexStorageReply

/**
 * This actor encapsulates storing Index objects in a cache,
 * in this case the cache is a MongoDB collection accessed
 * using the Casbah library.
 *
 * We're using a MongoDB "capped collection," see:
 *   http://www.mongodb.org/display/DOCS/Capped+Collections
 */
class IndexStorageActor(mongoURI: Option[String]) extends Actor {
    

    // MongoCollection is safe to use from multiple threads
    private class Worker(cache: MongoCollection)
        extends Actor {
        import IndexStorageActor._

        override def receive = {
            case GetCacheSize =>
                sender ! CacheSize(cache.getCount())

            case CacheIndex(url, index) =>
                cache.insert(MongoDBObject("url" -> url,
                    "time" -> System.currentTimeMillis().toDouble,
                    "index" -> indexAsDBObject(index)))
                sender ! IndexCached(url)

            case FetchCachedIndex(url) =>
                // "$natural" -> -1 means reverse insertion order
                // i.e. most recent
                val cursor =
                    cache.find(MongoDBObject("url" -> url))
                        .sort(MongoDBObject("$natural" -> -1))
                        .limit(1)

                val indexes = cursor flatMap { entry =>
                    require(entry.get("url") == url)
                    val time = entry.get("time") match {
                        case null => 0L
                        case x: java.lang.Double => x.toLong
                        case x: java.lang.Integer => x.toLong
                        case x: java.lang.Long => x.toLong
                        case _ => 0L
                    }
                    val millisInDay = TimeUnit.DAYS.toMillis(1)
                    if ((System.currentTimeMillis() - time) > millisInDay) {
                        // cache entry is too old, ignore
                        Nil
                    } else {
                        entry.get("index") match {
                            case null =>
                                Nil
                            case obj: DBObject =>
                                Seq(dbobjectAsIndex(obj))
                            case _ =>
                                Nil
                        }
                    }
                }

                if (indexes.hasNext) {
                    sender ! CachedIndexFetched(Some(indexes.next()))
                } else {
                    sender ! CachedIndexFetched(None)
                }
        }
    }

    override def receive = {
        case DropCache =>
            // DropCache is done here instead of in a worker so we
            // block new messages on the whole pool. There's no super
            // convenient way to be sure all delegates are done
            // touching mongo so we stick in an unreliable timeout
            // and hope for the best. In your mission-critical app,
            // improve on this ...
            Thread.sleep(2000)
            cache foreach { c => c.drop() }
            recreateCache()
        case m =>
            // send other messages to the pool
            router forward m
    }

    private[this] var connection: Option[MongoConnection] = None
    private[this] var database: Option[MongoDB] = None
    private[this] var cache: Option[MongoCollection] = None
    private[this] val router = context.actorOf(Props(new Worker(cache.get)).withRouter(RoundRobinRouter()), "router")

    private val cacheName = "indexCache"

    private def recreateCache() = {
        val db = database.get
        val c = cache.get

        if (!db.collectionNames.contains(cacheName)) {
            // here we're creating a capped collection, which is
            // basically a FIFO queue that drops the oldest entries
            // once it gets too big.
            val sizeBytes = 1024 * 1024 * 256
            val maxItems = 200

            db.createCollection(cacheName,
                MongoDBObject("capped" -> true,
                    "size" -> sizeBytes,
                    "max" -> maxItems))
        }

        // we build an index on the capped collection, which makes it
        // slower, but since our standard operation is to look up by
        // url, we want the index. One odd thing is that the index
        // is NOT unique, because capped collections never delete anything;
        // however, capped collections are guaranteed to stay in insertion
        // order ("natural order") and so if we search in reverse natural
        // order we'll always get the most recent entry.
        c.ensureIndex(MongoDBObject("url" -> 1), "url_index", unique = false)
        c.setWriteConcern(WriteConcern.Safe)
    }

    override def preStart() = {
        // Open connection to MongoDB and set up our collection

        /* Unfortunately, mongo-java-driver (and therefore Casbah) will
         * ignore the port, username, and password in the URI.
         * Moreover, the MongoURI class doesn't parse them correctly.
         * So we have to manually extract and use them even
         * though a MongoConnection(u:MongoURI) exists.
         * Doing this manually we don't use replica sets
         * in the URL correctly, but at least we can connect
         * in the simple case.
         */
        val uri = {
            val parsed = IndexStorageActor.parseMongoURI(mongoURI.getOrElse("mongodb:///"))
                .getOrElse(throw new Exception("bad mongo URI:" + mongoURI))
            // "/" for the URI path means no database was in the URI
            if (parsed.database == Some("/") || parsed.database == Some(""))
                parsed.copy(database = None)
            else
                parsed
        }

        connection = Some(MongoConnection(uri.host, uri.port))
        val dbname = uri.database.getOrElse("webwords")

        database = connection map { c => c(dbname) }

        uri.user foreach { username =>
            database.get.authenticate(username, uri.password.orNull)
        }

        cache = database map { db => db(cacheName) }

        recreateCache()
    }

    override def postStop() = {
        // close connection to MongoDB

        connection foreach { c => c.underlying.close() }
        connection = None
        database = None
        cache = None
    }
}

object IndexStorageActor {
    private[common] def indexAsDBObject(index: Index): DBObject = {
        val linksBuilder = MongoDBList.newBuilder

        for (link <- index.links) {
            linksBuilder += MongoDBObject("text" -> link._1,
                "href" -> link._2)
        }

        val countsBuilder = MongoDBList.newBuilder
        for (count <- index.wordCounts) {
            countsBuilder += MongoDBObject("word" -> count._1,
                "n" -> count._2)
        }

        MongoDBObject("links" -> linksBuilder.result,
            "wordCounts" -> countsBuilder.result)
    }

    private def getScalaSeq(obj: DBObject, key: String): Seq[_] = {
        // this supports DBObject from raw Java or from Casbah
        obj.get(key) match {
            case null =>
                Nil
            case list: BasicBSONList =>
                list.asScala
            case list: Seq[_] =>
                list
            case list: java.util.List[_] =>
                list.asScala
        }
    }

    private[common] def dbobjectAsIndex(obj: DBObject): Index = {
        // this is super-paranoid trying to ignore
        // bogus DB contents, which is probably overkill.

        val links = getScalaSeq(obj, "links") flatMap { item =>
            val raw = item match {
                case null =>
                    None
                case o: DBObject =>
                    (o.get("text"), o.get("href"))
            }
            raw match {
                case (text: String, href: String) if text != null && href != null =>
                    Some(text -> href)
                case _ =>
                    None
            }
        }

        val wordCounts = getScalaSeq(obj, "wordCounts") flatMap { item =>
            val raw = item match {
                case null =>
                    None
                case o: DBObject =>
                    (o.get("word"), o.get("n"))
            }
            raw match {
                case (word: String, count: Number) if word != null && count != null =>
                    Some(word -> count.intValue)
                case _ =>
                    None
            }
        }

        Index(links, wordCounts)
    }

    private[common] case class MongoURIParts(user: Option[String], password: Option[String],
        host: String, port: Int, database: Option[String])

    // the MongoURI class in mongo-java-parser is broken and blows up
    // with port, username, and password involved, so we need this.
    private[common] def parseMongoURI(s: String): Option[MongoURIParts] = {
        val mongoDefaults = URIParts(scheme = "mongodb", host = Some("localhost"), port = Some(27017),
            user = None, password = None, path = None)
        expandURI(s, mongoDefaults) flatMap { parts =>
            if (parts.scheme != "mongodb")
                None
            else
                Some(MongoURIParts(user = parts.user, password = parts.password,
                    host = parts.host.get, port = parts.port.get,
                    database = parts.path))
        }
    }
}
