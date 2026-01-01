package org.barter.errors

data class GenericServerError (val httpStatus: Int, val message:String)