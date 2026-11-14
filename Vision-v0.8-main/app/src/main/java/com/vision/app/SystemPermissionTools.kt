package com.vision.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * System Permission-based tools: contacts, location, files.
 * Vision can read the user's contacts, location and photos once he allows it.
 */
object SystemPermissionTools {

    fun declarations() = JSONArray().apply {
        put(d("list_contacts", "List saved contact names and numbers.", "filter" to "Name filter (optional)"))
        put(d("get_location", "Get current GPS location (latitude, longitude)."))
        put(d("list_photos", "List recent photos in gallery."))
        put(d("read_contact_details", "Get a contact's number and email.", "name" to "Contact name"))
        put(d("call_contact", "Call a saved contact right now. Use it only when he clearly tells you to call; it places the call by itself when the phone permission is granted.", "name" to "Contact name"))
        put(d("call_number", "Call a phone number right now, e.g. an emergency number such as 112, 100 or 108, or a number he dictated. Digits only, optional leading +.", "number" to "phone number"))
    }

    suspend fun run(ctx: Context, name: String, args: JSONObject): String {
        return when (name) {
            "list_contacts" -> listContacts(ctx, args.optString("filter"))
            "get_location" -> getLocation(ctx)
            "list_photos" -> listPhotos(ctx)
            "read_contact_details" -> readContact(ctx, args.optString("name"))
            "call_contact" -> dialContact(ctx, args.optString("name"))
            "call_number" -> callNumber(ctx, args.optString("number"))
            else -> "Unknown tool: $name"
        }
    }

    private fun listContacts(ctx: Context, filter: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return "Contacts permission not granted."
        val cr = ctx.contentResolver
        val c = cr.query(android.provider.ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
        val out = mutableListOf<String>()
        c?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.DISPLAY_NAME))
                if (filter.isBlank() || name.contains(filter, ignoreCase = true)) out.add(name)
            }
        }
        return out.take(20).joinToString("\n")
    }

    private fun getLocation(ctx: Context): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return "Location permission not granted."
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        return loc?.let { "Lat: ${it.latitude}, Lon: ${it.longitude}" } ?: "Location unavailable."
    }

    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun listPhotos(ctx: Context): String {
        if (ContextCompat.checkSelfPermission(ctx, photoPermission())
            != PackageManager.PERMISSION_GRANTED) return "Storage permission not granted."
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val c = ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 10")
        val out = mutableListOf<String>()
        c?.use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out.joinToString("\n")
    }

    private fun dialContact(ctx: Context, name: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "Contacts permission not granted. Enable it in Android settings."
        val wanted = name.trim()
        if (wanted.isBlank()) return "Tell me the contact name."
        val cr = ctx.contentResolver
        val matches = mutableListOf<Pair<String, String>>()
        val c = cr.query(android.provider.ContactsContract.Contacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME),
            "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$wanted%"), null)
        c?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val display = cursor.getString(1) ?: continue
                val pc = cr.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null)
                pc?.use { phones -> while (phones.moveToNext()) {
                    val number = phones.getString(0)?.trim().orEmpty()
                    if (number.isNotBlank()) matches.add(display to number)
                } }
            }
        }
        if (matches.isEmpty()) return "No phone number found for $wanted."
        val distinct = matches.distinctBy { it.second }
        if (distinct.size > 1) return "More than one number matches. Say the exact contact name."
        return startCall(ctx, distinct.first().second, distinct.first().first)
    }

    private fun callNumber(ctx: Context, raw: String): String {
        val number = raw.filter { it.isDigit() || it == '+' }
        if (!Regex("""\+?[0-9]{3,15}""").matches(number)) return "That is not a phone number I can call."
        return startCall(ctx, number, number)
    }

    /** Places the call directly when CALL_PHONE is granted; otherwise opens the dialer with the number filled in. */
    private fun startCall(ctx: Context, number: String, label: String): String {
        val uri = android.net.Uri.fromParts("tel", number, null)
        val newTask = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_CALL, uri).addFlags(newTask))
                "Calling $label."
            } else {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, uri).addFlags(newTask))
                "The call permission is not granted, so the dialer is open for $label. Ask him to allow Phone calls for Vision, or tap Call."
            }
        } catch (e: Exception) { "Could not start the call: ${e.message}" }
    }

    private fun readContact(ctx: Context, name: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return "Contacts permission not granted."
        val cr = ctx.contentResolver
        val c = cr.query(android.provider.ContactsContract.Contacts.CONTENT_URI, null,
            "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
        c?.use {
            if (it.moveToFirst()) {
                val id = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts._ID))
                val pc = cr.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null)
                pc?.use { pv -> if (pv.moveToFirst()) return pv.getString(pv.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) }
            }
        }
        return "Contact not found."
    }

    private fun d(name: String, desc: String, vararg p: Pair<String, String>): JSONObject {
        val props = JSONObject()
        p.forEach { props.put(it.first, JSONObject().put("type", "STRING").put("description", it.second)) }
        val o = JSONObject().put("name", name).put("description", desc)
        if (p.isNotEmpty()) o.put("parameters", JSONObject()
            .put("type", "OBJECT").put("properties", props).put("required", JSONArray(p.map { it.first })))
        return o
    }
}
