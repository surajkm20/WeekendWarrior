const mongoose = require("mongoose");

// Schema
const usersSchema = new mongoose.Schema({
    firstName: {
        type: String,
        required: true,
    },
    lastName:{
        type: String,
        required: true,
    },
    email:{
        type: String,
        required: true,
        unique: true,
    },
    jobTitle:{
        type: String,
    },
    gender:{
        type:String,
    },
}, { timestamps:true }
);

// prepre Model
const User = mongoose.model("user", usersSchema);

module.exports = User;