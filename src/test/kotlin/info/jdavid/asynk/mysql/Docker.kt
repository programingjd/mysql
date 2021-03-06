package info.jdavid.asynk.mysql

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.ContentType
import org.apache.http.impl.client.HttpClients
import java.io.File
import java.net.URLEncoder

object Docker {

  private fun dataPath(): File {
    var dir: File? = File(this::class.java.protectionDomain.codeSource.location.toURI())
    while (dir != null) {
      if (File(dir, ".git").exists()) break
      dir = dir.parentFile
    }
    dir = File(dir, "data")
    return dir.canonicalFile
  }

  private val dockerApiUrl = "http://localhost:2375"

  enum class DatabaseVersion(val label: String, val port: Int, val sha256password: Boolean = false) {
    MYSQL_55("mysql/mysql-server:5.5", 8155),
    MYSQL_56("mysql/mysql-server:5.6", 8156),
    MYSQL_57("mysql/mysql-server:5.7", 8157),
    MYSQL_57_SHA256("mysql/mysql-server:5.7", 8157, true),
    MYSQL_80("mysql/mysql-server:8.0", 8158),
    MARIADB_55("library/mariadb:5.5", 8255),
    MARIADB_100("library/mariadb:10.0", 8210) //,
//    MARIADB_101("library/mariadb:10.1", 8211),
//    MARIADB_102("library/mariadb:10.2", 8212),
//    MARIADB_103("library/mariadb:10.3", 8213),
//    MARIADB_104("library/mariadb:10.4", 8214)
  }

  fun check() {
    HttpClients.createMinimal().let {
      try {
        it.execute(HttpGet("${dockerApiUrl}/version")).use {
          if (it.statusLine.statusCode != 200) {
            throw RuntimeException("Docker is unreachable.")
          }
        }
      }
      catch (e: Exception) {
        println(
          "Docker did not respond. Please make sure that docker is running and that the option to expose " +
            "the daemon on tcp without TLS is enabled in the settings."
        )
        e.printStackTrace()
        throw e
      }
    }
  }

  fun startContainer(databaseVersion: DatabaseVersion) {
    HttpClients.createMinimal().let {
      it.execute(HttpPost(
        "${dockerApiUrl}/images/create?fromImage=${databaseVersion.label}"
      )).use {
        println(String(it.entity.content.readBytes()))
      }
      it.execute(HttpPost(
        "${dockerApiUrl}/containers/create?name=async_${databaseVersion.name.toLowerCase()}"
      ).apply {
        val body = mutableMapOf(
          "Image" to databaseVersion.label,
          "Env" to listOf(
            "MYSQL_ROOT_PASSWORD=root",
            "MYSQL_DATABASE=world",
            "MYSQL_USER=test",
            "MYSQL_PASSWORD=asynk",
            "MYSQL_LOG_CONSOLE=true"
          ),
          "HostConfig" to mapOf(
            "Binds" to listOf("${dataPath().path.replace('\\','/')}:/docker-entrypoint-initdb.d/"),
            "PortBindings" to mapOf(
              "3306/tcp" to listOf(
                mapOf("HostPort" to "${databaseVersion.port}")
              )
            )
          ),
          "ExposedPorts" to mapOf("3306/tcp" to emptyMap<String, Any>())
        )
        if (databaseVersion.sha256password) {
          body.put(
            "Cmd",
            listOf("mysqld", "--default-authentication-plugin=sha256_password")
          )
        }
        if (databaseVersion.label.startsWith("library")) {
          body.put(
            "Healthcheck",
            mapOf(
              "Test" to listOf(
                "CMD-SHELL", "mysql --user=root --password=root --execute \"SHOW DATABASES;\""
              ),
              "Interval" to 10000000000,
              "Timeout" to 5000000000,
              "Retries" to 5,
              "StartPeriod" to 0
            )
          )
        }
        entity = ByteArrayEntity(ObjectMapper().writeValueAsBytes(body), ContentType.APPLICATION_JSON)
      }).use {
        println(String(it.entity.content.readBytes()))
      }

      it.execute(HttpPost(
        "${dockerApiUrl}/containers/async_${databaseVersion.name.toLowerCase()}/start"
      )).use {
        if (it.statusLine.statusCode != 204 && it.statusLine.statusCode != 304)
          throw RuntimeException(String(it.entity.content.readBytes()))
      }

      val id = it.execute(HttpGet(
        "${dockerApiUrl}/containers/json?name=async_${databaseVersion.name.toLowerCase()}"
      )).use {
        val list = ObjectMapper().readValue(it.entity.content.readBytes(), ArrayList::class.java)
        if (list.isEmpty()) throw RuntimeException("Failed to create container.")
        @Suppress("UNCHECKED_CAST")
        (list[0] as Map<String, Any?>)["Id"] as String
      }

      print("Waiting for container to start.")
      var counter = 0
      val filters = "{\"id\":[\"${id}\"],\"health\":[\"healthy\",\"none\"]}"
      while (true) {
        val list = it.execute(HttpGet(
          "${dockerApiUrl}/containers/json?filters=${URLEncoder.encode(filters,"UTF-8")}"
        )).use {
          ObjectMapper().readValue(it.entity.content.readBytes(), ArrayList::class.java)
        }
//        val found = list.find {
//          @Suppress("UNCHECKED_CAST")
//          (list[0] as Map<String, Any?>)["Id"] == id
//        }
//        if (found != null) break
        if (list.size > 0) break
        if (++counter < 90) {
          Thread.sleep(2000)
          print(".")
        }
        else throw RuntimeException("Failed to start container.")
      }
      println()
    }
  }

  fun stopContainer(databaseVersion: DatabaseVersion) {
    HttpClients.createMinimal().let {
      it.execute(HttpPost(
        "${dockerApiUrl}/containers/async_${databaseVersion.name.toLowerCase()}/stop"
      )).use {
        if (it.statusLine.statusCode != 204 && it.statusLine.statusCode != 304)
          throw RuntimeException(String(it.entity.content.readBytes()))
      }
    }
    HttpClients.createMinimal().let {
      it.execute(HttpDelete(
        "${dockerApiUrl}/containers/async_${databaseVersion.name.toLowerCase()}"
      )).use {
        if (it.statusLine.statusCode != 204 && it.statusLine.statusCode != 404)
          throw RuntimeException(String(it.entity.content.readBytes()))
      }
    }
  }

  fun createWorldDatabase(databaseVersion: DatabaseVersion) {
    HttpClients.createMinimal().let {
      val id = it.execute(HttpPost(
        "${dockerApiUrl}/containers/async_${databaseVersion.name.toLowerCase()}/exec"
      ).apply {
        val body = mapOf(
          "Cmd" to listOf(
            "mysql -uroot -proot < ${File(dataPath(), "world.sql").path}"
          )
        )
        entity = ByteArrayEntity(ObjectMapper().writeValueAsBytes(body), ContentType.APPLICATION_JSON)
      }).use {
        ObjectMapper().readTree(it.entity.content.readBytes()).findValue("Id").asText()
      }
      it.execute(HttpPost(
        "${dockerApiUrl}/exec/${id}/start"
      )).use {
        println(String(it.entity.content.readBytes()))
      }
      it.execute(HttpGet(
        "${dockerApiUrl}/exec/${id}/json"
      )).use {
        println(String(it.entity.content.readBytes()))
      }
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    check()
    DatabaseVersion.values().last().let { version ->
      startContainer(version)
      try {
        //createWorldDatabase(version)
      }
      finally {
        stopContainer(version)
      }
    }
  }

}
