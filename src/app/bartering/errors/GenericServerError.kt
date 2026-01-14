package app.bartering.errors

data class GenericServerError (val httpStatus: Int, val message:String)